package nl.topicus.bitbucket.api;

import com.atlassian.bitbucket.event.branch.BranchCreatedEvent;
import com.atlassian.bitbucket.event.branch.BranchDeletedEvent;
import com.atlassian.bitbucket.event.pull.*;
import com.atlassian.bitbucket.event.repository.RepositoryDeletionRequestedEvent;
import com.atlassian.bitbucket.event.repository.RepositoryRefsChangedEvent;
import com.atlassian.bitbucket.event.tag.TagCreatedEvent;
import com.atlassian.bitbucket.nav.NavBuilder;
import com.atlassian.bitbucket.pull.IllegalPullRequestStateException;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.server.ApplicationPropertiesService;
import com.atlassian.event.api.EventListener;
import com.atlassian.event.api.EventPublisher;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import nl.topicus.bitbucket.events.BitbucketPushEvent;
import nl.topicus.bitbucket.events.BitbucketServerPullRequestEvent;
import nl.topicus.bitbucket.events.EventType;
import nl.topicus.bitbucket.events.Events;
import nl.topicus.bitbucket.persistence.WebHookConfiguration;
import nl.topicus.bitbucket.persistence.WebHookConfigurationDao;
import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHeader;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ExecutorService;

@Component
public class PullRequestListener implements DisposableBean, InitializingBean
{
    private static final Logger LOGGER = LoggerFactory.getLogger(PullRequestListener.class);

    private final ApplicationPropertiesService applicationPropertiesService;
    private final EventPublisher eventPublisher;
    private final ExecutorService executorService;
    private final CloseableHttpClient httpClient;
    private final NavBuilder navBuilder;
    private final PullRequestService pullRequestService;
    private final WebHookConfigurationDao webHookConfigurationDao;

    @Autowired
    public PullRequestListener(@ComponentImport ApplicationPropertiesService applicationPropertiesService,
                               @ComponentImport EventPublisher eventPublisher,
                               @ComponentImport ExecutorService executorService,
                               HttpClientFactory httpClientFactory,
                               @ComponentImport NavBuilder navBuilder,
                               @ComponentImport PullRequestService pullRequestService,
                               WebHookConfigurationDao webHookConfigurationDao)
    {
        this.applicationPropertiesService = applicationPropertiesService;
        this.eventPublisher = eventPublisher;
        this.executorService = executorService;
        this.navBuilder = navBuilder;
        this.pullRequestService = pullRequestService;
        this.webHookConfigurationDao = webHookConfigurationDao;

        httpClient = httpClientFactory.create();
    }

    @Override
    public void afterPropertiesSet()
    {
        eventPublisher.register(this);
    }

    @EventListener
    public void onPullRequestCreated(PullRequestOpenedEvent event)
    {
        sendPullRequestEvent(event, EventType.PULL_REQUEST_CREATED, true);
    }

    @Override
    public void destroy()
    {
        eventPublisher.unregister(this);

        try
        {
            httpClient.close();
        }
        catch (IOException e)
        {
            LOGGER.warn("Failed to close HttpClient; it will be abandoned", e);
        }
    }

    @EventListener
    public void onPullRequestUpdated(PullRequestUpdatedEvent event)
    {
        sendPullRequestEvent(event, EventType.PULL_REQUEST_UPDATED);
    }

    @EventListener
    public void onPullRequestReopened(PullRequestReopenedEvent event)
    {
        PullRequest pullRequest = event.getPullRequest();
        if (pullRequest.getFromRef().getLatestCommit().equals(event.getPreviousFromHash()) &&
                pullRequest.getToRef().getLatestCommit().equals(event.getPreviousToHash()))
        {
            // If the PullRequest's from and to refs were not updated when it was reopend, trigger webhooks
            // for the update. Otherwise, if _either_ ref changed, a rescope event will also be raised. Let
            // that trigger webhooks instead, to ensure the pull request's refs are updated on disk
            sendPullRequestEvent(event, EventType.PULL_REQUEST_REOPENED);
        }
    }

    @EventListener
    public void onPullRequestRescoped(PullRequestRescopedEvent event)
    {
        // see this atlassian page for explanation of the logic in this handler:
        // https://answers.atlassian.com/questions/239988

        // only trigger when changes were pushed to the "from" side of the PR
        if (event.isFromHashUpdated())
        {
            sendPullRequestEvent(event, EventType.PULL_REQUEST_RESCOPED, true);
        }
    }

    @EventListener
    public void onPullRequestMerged(PullRequestMergedEvent event)
    {
        // Note that onRepositoryRefsChanged is _also_ called for this same event. It triggers a different
        // set of webhooks, however, so the double-handling is intentional
        sendPullRequestEvent(event, EventType.PULL_REQUEST_MERGED);
    }

    @EventListener
    public void onPullRequestDeclined(PullRequestDeclinedEvent event)
    {
        sendPullRequestEvent(event, EventType.PULL_REQUEST_DECLINED);
    }

    /**
     * Delete configurations for any webhooks associated with the repository.
     * <p>
     * Even if the repository was "recreated", it would have a new ID, which means the old configurations would still
     * not be reused.
     * <p>
     * This event is raised as part fo the same database transaction where the repository is being deleted. If that
     * transaction is committed, deleting the configurations will also commit. Otherwise, if something goes wrong,
     * or if another listener {@link RepositoryDeletionRequestedEvent#cancel cancels} the event, this change will be
     * rolled back as part of that transaction.
     *
     * @param event the deletion request
     */
    @EventListener
    public void onRepositoryDeletionRequested(RepositoryDeletionRequestedEvent event) {
        // If the event has already been canceled by another listener, don't bother deleting. The transaction is
        // going to be rolled back anyway
        if (!event.isCanceled()) {
            webHookConfigurationDao.deleteWebhookConfigurations(event.getRepository());
        }
    }

    @EventListener
    public void onRepositoryRefsChanged(RepositoryRefsChangedEvent event)
    {
        executorService.submit(() -> {
            BitbucketPushEvent pushEvent = Events.createPushEvent(event, applicationPropertiesService);
            sendEvents(pushEvent, event.getRepository(), chooseRefsChangedEvent(event));
        });
    }

    private static EventType chooseRefsChangedEvent(RepositoryRefsChangedEvent event)
    {
        if (event instanceof BranchCreatedEvent)
        {
            return EventType.BRANCH_CREATED;
        }
        if (event instanceof BranchDeletedEvent)
        {
            return EventType.BRANCH_DELETED;
        }
        if (event instanceof TagCreatedEvent)
        {
            return EventType.TAG_CREATED;
        }
        return EventType.REPO_PUSH;
    }

    private void sendPullRequestEvent(PullRequestEvent event, EventType eventType)
    {
        sendPullRequestEvent(event, eventType, false);
    }

    private void sendPullRequestEvent(PullRequestEvent event, EventType eventType, boolean updateRefs)
    {
        executorService.submit(() -> {
            PullRequest pullRequest = event.getPullRequest();
            if (updateRefs && pullRequest.isOpen())
            {
                try
                {
                    pullRequestService.canMerge(pullRequest.getToRef().getRepository().getId(), pullRequest.getId());
                }
                catch (IllegalPullRequestStateException e)
                {
                    // Logging "e.getMessage()" rather than "e" here is intentional. We don't want to spam
                    // the full stack trace in the logs; it doesn't add anything here.
                    LOGGER.debug("{}: {}", pullRequest, e.getMessage());
                }
            }

            Repository repository = pullRequest.getToRef().getRepository();
            String prUrl = navBuilder.repo(repository).pullRequest(pullRequest.getId()).buildAbsolute();

            BitbucketServerPullRequestEvent pullRequestEvent =
                    Events.createPullrequestEvent(event, applicationPropertiesService);
            pullRequestEvent.getPullrequest().setLink(prUrl);

            sendEvents(pullRequestEvent, repository, eventType);
        });
    }

    private void sendEvents(Object event, Repository repo, EventType eventType)
    {
        String body;
        try
        {
            body = new ObjectMapper().writeValueAsString(event);
        }
        catch (IOException e)
        {
            LOGGER.error("[repo: {}]| Failed to marshal {} payload to JSON. The event will be discarded",
                    repo, eventType);
            return;
        }

        HttpPost post = new HttpPost();
        post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        post.setHeaders(new Header[]{
                new BasicHeader("X-Event-Key", eventType.getHeaderValue()),
                new BasicHeader("X-Bitbucket-Type", "server")
        });

        for (WebHookConfiguration webHookConfiguration : webHookConfigurationDao.getEnabledWebHookConfigurations(repo, eventType))
        {
            post.setURI(URI.create(webHookConfiguration.getURL()));

            try (CloseableHttpResponse response = httpClient.execute(post))
            {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode >= 400)
                {
                    LOGGER.error(
                            "[repo: {}]| Something went wrong while posting (response code:{}) the following body to webhook: [{}({})] \n{}",
                            repo,
                            statusCode,
                            webHookConfiguration.getTitle(),
                            webHookConfiguration.getURL(),
                            body);
                }
            }
            catch (IOException e)
            {
                LOGGER.error(
                        "[repo: {}]| Something went wrong while posting the following body to webhook: [{}({})] \n{}",
                        repo,
                        webHookConfiguration.getTitle(),
                        webHookConfiguration.getURL(),
                        body,
                        e);
            }
        }
    }
}

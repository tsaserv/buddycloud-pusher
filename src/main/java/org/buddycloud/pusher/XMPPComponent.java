package org.buddycloud.pusher;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.buddycloud.pusher.db.DataSource;
import org.buddycloud.pusher.handler.GetNotificationSettingsQueryHandler;
import org.buddycloud.pusher.handler.GetPusherMetadataQueryHandler;
import org.buddycloud.pusher.handler.PasswordResetQueryHandler;
import org.buddycloud.pusher.handler.QueryHandler;
import org.buddycloud.pusher.handler.SetNotificationSettingsQueryHandler;
import org.buddycloud.pusher.handler.SignupQueryHandler;
import org.buddycloud.pusher.handler.UnregisterQueryHandler;
import org.buddycloud.pusher.handler.internal.DeleteUserQueryHandler;
import org.buddycloud.pusher.handler.internal.FollowRequestApprovedQueryHandler;
import org.buddycloud.pusher.handler.internal.FollowRequestDeniedQueryHandler;
import org.buddycloud.pusher.handler.internal.FollowRequestQueryHandler;
import org.buddycloud.pusher.handler.internal.UserFollowedQueryHandler;
import org.buddycloud.pusher.handler.internal.UserPostedAfterMyPostQueryHandler;
import org.buddycloud.pusher.handler.internal.UserPostedMentionQueryHandler;
import org.buddycloud.pusher.handler.internal.UserPostedOnMyChannelQueryHandler;
import org.buddycloud.pusher.handler.internal.UserPostedOnSubscribedChannelQueryHandler;
import org.buddycloud.pusher.handler.internal.UserUnfollowedQueryHandler;
import org.buddycloud.pusher.message.MessageProcessor;
import org.buddycloud.pusher.utils.XMPPUtils;
import org.dom4j.Element;
import org.dom4j.Namespace;
import org.jivesoftware.smack.SmackConfiguration;
import org.jivesoftware.smack.util.StringUtils;
import org.xmpp.component.AbstractComponent;
import org.xmpp.packet.IQ;
import org.xmpp.packet.Message;


/**
 * @author Abmar
 *
 */
public class XMPPComponent extends AbstractComponent {

	private static final String DESCRIPTION = "Pusher service for buddycloud";
	private static final String NAME = "Buddycloud pusher";
	private static final Logger LOGGER = Logger.getLogger(XMPPComponent.class);
	
	private final Map<String, QueryHandler> queryGetHandlers = new HashMap<String, QueryHandler>();
	private final Map<String, QueryHandler> querySetHandlers = new HashMap<String, QueryHandler>();
	private final Map<String, QueryHandler> loHandlers = new HashMap<String, QueryHandler>();
	private final Map<String, BlockingQueue<IQ>> iqResultQueues = new HashMap<String, BlockingQueue<IQ>>();
	
	private final Properties configuration;
	private DataSource dataSource;
	private MessageProcessor messageConsumer;
	
	/**
	 * @param configuration
	 */
	public XMPPComponent(Properties configuration) {
		this.configuration = configuration;
		this.dataSource = new DataSource(configuration);
		
		initHandlers();
		LOGGER.debug("XMPP component initialized.");
	}

	/**
	 * 
	 */
	private void initHandlers() {
		// Get handlers
		addHandler(new GetNotificationSettingsQueryHandler(configuration, dataSource), queryGetHandlers);
		addHandler(new GetPusherMetadataQueryHandler(configuration, dataSource), queryGetHandlers);
		// Set handlers
		addHandler(new SetNotificationSettingsQueryHandler(configuration, dataSource), querySetHandlers);
		addHandler(new SignupQueryHandler(configuration, dataSource), querySetHandlers);
		addHandler(new PasswordResetQueryHandler(this, configuration, dataSource), querySetHandlers);
		addHandler(new UnregisterQueryHandler(configuration, dataSource), querySetHandlers);
		// Loopback handlers
		addHandler(new FollowRequestQueryHandler(configuration, dataSource), loHandlers);
		addHandler(new FollowRequestApprovedQueryHandler(configuration, dataSource), loHandlers);
		addHandler(new FollowRequestDeniedQueryHandler(configuration, dataSource), loHandlers);
		addHandler(new DeleteUserQueryHandler(configuration, dataSource), loHandlers);
		addHandler(new UserFollowedQueryHandler(configuration, dataSource), loHandlers);
		addHandler(new UserUnfollowedQueryHandler(configuration, dataSource), loHandlers);
		addHandler(new UserPostedAfterMyPostQueryHandler(configuration, dataSource), loHandlers);
		addHandler(new UserPostedMentionQueryHandler(configuration, dataSource), loHandlers);
		addHandler(new UserPostedOnMyChannelQueryHandler(configuration, dataSource), loHandlers);
		addHandler(new UserPostedOnSubscribedChannelQueryHandler(configuration, dataSource), loHandlers);
		// Message consumer
		this.messageConsumer = new MessageProcessor(this);
	}

	private void addHandler(QueryHandler queryHandler, Map<String, QueryHandler> handlers) {
		handlers.put(queryHandler.getNamespace(), queryHandler);
	}

	/* (non-Javadoc)
	 * @see org.xmpp.component.AbstractComponent#handleIQSet(org.xmpp.packet.IQ)
	 */
	@Override
	protected IQ handleIQSet(IQ iq) throws Exception {
		return handle(iq, querySetHandlers);
	}
	
	@Override
	protected IQ handleIQGet(IQ iq) throws Exception {
		return handle(iq, queryGetHandlers);
	}
	
	public IQ handleIQLoopback(IQ iq) throws Exception {
		return handle(iq, loHandlers);
	}
	
	@Override
	protected void handleIQError(IQ iq) {
		BlockingQueue<IQ> queue = iqResultQueues.get(iq.getID());
		if (queue != null) {
			queue.add(iq);
		}
		super.handleIQError(iq);
	}
	
	@Override
	protected void handleIQResult(IQ iq) {
		BlockingQueue<IQ> queue = iqResultQueues.get(iq.getID());
		if (queue != null) {
			queue.add(iq);
		}
	}
	
	public IQ syncIQ(IQ iq) {
		String iqId = StringUtils.randomString(6);
		iq.setID(iqId);
		
		BlockingQueue<IQ> queue = new LinkedBlockingDeque<IQ>();
		iqResultQueues.put(iqId, queue);
		LOGGER.debug("S: " + iq.toXML());
		send(iq);
		
		try {
			IQ poll = queue.poll(SmackConfiguration.getPacketReplyTimeout(), TimeUnit.SECONDS);
			return poll;
		} catch (InterruptedException e) {
			return null;
		} finally {
			iqResultQueues.remove(iqId);
		}
	}
	
	@Override
	protected void handleMessage(Message message) {
		messageConsumer.consume(message);
	}
	
	private IQ handle(IQ iq, Map<String, QueryHandler> handlers) {
		Element queryElement = iq.getElement().element("query");
		if (queryElement == null) {
			return XMPPUtils.error(iq, "IQ does not contain query element.", 
					LOGGER);
		}
		
		Namespace namespace = queryElement.getNamespace();
		
		QueryHandler queryHandler = handlers.get(namespace.getURI());
		if (queryHandler == null) {
			return XMPPUtils.error(iq, "QueryHandler not found for namespace: " + namespace, 
					LOGGER);
		}
		
		return queryHandler.handle(iq);
	}
	
	/* (non-Javadoc)
	 * @see org.xmpp.component.AbstractComponent#getDescription()
	 */
	@Override
	public String getDescription() {
		return DESCRIPTION;
	}

	/* (non-Javadoc)
	 * @see org.xmpp.component.AbstractComponent#getName()
	 */
	@Override
	public String getName() {
		return NAME;
	}

	@Override 
	protected String discoInfoIdentityCategory() {
		return ("Pusher");
	}

	@Override 
	protected String discoInfoIdentityCategoryType() {
		return ("Notification");
	}
	
	public String getProperty(String property) {
		return configuration.getProperty(property);
	}

}

/*
 * Copyright (c) 2014 Peter Palaga.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.l2x6.eircc.core;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import org.l2x6.eircc.core.client.IrcClient;
import org.l2x6.eircc.core.model.AbstractIrcChannel;
import org.l2x6.eircc.core.model.IrcAccount;
import org.l2x6.eircc.core.model.IrcAccount.IrcAccountState;
import org.l2x6.eircc.core.model.IrcServer;
import org.l2x6.eircc.core.model.IrcUser;
import org.l2x6.eircc.core.util.IrcUtils;
import org.schwering.irc.lib.IRCCommand;

/**
 * @author <a href="mailto:ppalaga@redhat.com">Peter Palaga</a>
 */
public class IrcController {
    private static final IrcController INSTANCE = new IrcController();

    public static IrcController getInstance() {
        return INSTANCE;
    }

    private final Map<UUID, IrcClient> clients = new HashMap<UUID, IrcClient>();

    /**
     *
     */
    public IrcController() {
        super();
    }

    public void changeNick(IrcServer server, String oldNick, String newNick, String username) {
        IrcUtils.assertUiThread();
        server.changeNick(oldNick, newNick, username);
    }

    public void connect(IrcAccount account) throws IrcException {
        IrcUtils.assertUiThread();
        getClientOrConnect(account);
    }

    public void dispose() {
        quitAll();
    }

    public AbstractIrcChannel getAccountChannel(IrcAccount ircAccount, String channelName) {
        IrcUtils.assertUiThread();
        AbstractIrcChannel result = ircAccount.findChannel(channelName);
        return result;
    }

    /**
     * @param account
     * @throws IOException
     */
    private IrcClient getClientOrConnect(IrcAccount account) throws IrcException {
        IrcClient client = clients.get(account.getId());
        if (client != null && !client.isConnected()) {
            client.close();
            client = null;
        }
        if (client == null) {
            client = new IrcClient();
            try {
                client.connect(account);
                clients.put(account.getId(), client);
            } catch (IrcException e) {
                account.setOffline(e);
                throw e;
            }
        }
        return client;
    }

    public AbstractIrcChannel getOrCreateAccountChannel(IrcAccount ircAccount, String channelName) {
        IrcUtils.assertUiThread();
        AbstractIrcChannel result = ircAccount.findChannel(channelName);
        if (result == null) {
            result = ircAccount.createChannel(channelName);
        }
        ircAccount.ensureChannelListed(result);
        return result;
    }

    /**
     * @param user
     * @return
     */
    public AbstractIrcChannel getOrCreateP2pChannel(IrcUser p2pUser) {
        IrcUtils.assertUiThread();
        IrcAccount account = p2pUser.getServer().getAccount();
        AbstractIrcChannel result = account.findP2pChannel(p2pUser);
        if (result == null) {
            result = account.createP2pChannel(p2pUser);
        }
        account.ensureChannelListed(result);
        return result;
    }

    public IrcUser getOrCreateUser(IrcServer server, String nick, String username) {
        IrcUtils.assertUiThread();
        IrcUser result = server.findUser(nick);
        if (result == null) {
            result = server.createUser(nick, username);
            server.addUser(result);
        }
        return result;
    }

    /**
     * @param channel
     * @throws IOException
     */
    public void joinChannel(AbstractIrcChannel channel) throws IrcException {
        IrcUtils.assertUiThread();
        if (!channel.isJoined()) {
            IrcClient client = getClientOrConnect(channel.getAccount());
            if (!channel.isP2p()) {
                client.joinChannel(channel);
            } else {
                /*
                 * there is no need to join p2p channels via client let us set
                 * it joined manually now after we have successfully called
                 * getClientOrConnect()
                 */
                channel.setJoined(true);
            }
        }
    }

    /**
     * @param account
     * @throws IOException
     */
    public void listChannels(IrcAccount account) throws IrcException {
        IrcUtils.assertUiThread();
        getClientOrConnect(account).listChannels();
    }

    /**
     * @param channel
     * @throws IOException
     */
    public void partChannel(AbstractIrcChannel channel) throws IrcException {
        IrcUtils.assertUiThread();
        if (channel.isJoined()) {
            if (!channel.isP2p()) {
                getClientOrConnect(channel.getAccount()).partChannel(channel);
            } else {
                /*
                 * simply mark as disconnected and remove from account list
                 */
                channel.setJoined(false);
                channel.getAccount().removeChannel(channel);
            }
        }
    }

    /**
     * @param channel
     * @param text
     * @throws IOException
     */
    public void postMessage(AbstractIrcChannel channel, String text) throws IrcException {
        IrcUtils.assertUiThread();
        IrcClient client = getClientOrConnect(channel.getAccount());

        IRCCommand cmd = IrcUtils.getInitialCommand(text);
        if (cmd != null) {
            client.postRaw(IrcUtils.getRawCommand(text));
        } else {
            client.postMessage(channel, text);
        }
    }

    public void quit(IrcAccount ircAccount) {
        IrcUtils.assertUiThread();
        IrcClient client = clients.remove(ircAccount.getId());
        if (client != null && client.isConnected()) {
            client.quitAndClose();
        }
    }

    public void quitAll() {
        IrcUtils.assertUiThread();
        for (Iterator<IrcClient> i = clients.values().iterator(); i.hasNext();) {
            IrcClient client = i.next();
            if (client != null && !client.isConnected()) {
                client.quitAndClose();
            }
        }
    }

    /**
     * @param unseenNicks
     * @throws IOException
     */
    public void resolveNicks(IrcServer server, Collection<String> nicks) throws IOException {
        IrcUtils.assertUiThread();
        //TODO nicks resolving
        //getClientOrConnect(server.getAccount()).whois(nicks);
    }
    /**
     * @param channel
     * @param nick
     */
    public void userLeft(AbstractIrcChannel channel, String nick, String msg) {
        IrcUtils.assertUiThread();
        if (nick.equals(channel.getAccount().getAcceptedNick())) {
            /* It is me who left */
            channel.setJoined(false);
        } else {
            if (channel.isPresent(nick)) {
                channel.removeNick(nick, msg);
            }
        }
    }

    public void userQuit(IrcAccount account, String nick, String msg) {
        IrcUtils.assertUiThread();
        if (nick.equals(account.getAcceptedNick())) {
            account.setState(IrcAccountState.OFFLINE);
        } else {
            /* someone else has quit */
            for (AbstractIrcChannel channel : account.getChannels()) {
                if (channel.isJoined()) {
                    userLeft(channel, nick, msg);
                }
            }
        }
    }

}

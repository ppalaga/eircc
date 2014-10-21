/*
 * Copyright (c) 2014 Peter Palaga.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.l2x6.eircc.core.model;

import java.time.OffsetDateTime;

import org.l2x6.eircc.ui.misc.Colors;

/**
 * @author <a href="mailto:ppalaga@redhat.com">Peter Palaga</a>
 */
public class IrcMessage extends PlainIrcMessage {
    final IrcLog log;
    private IrcNotificationLevel notificationLevel;

    protected final IrcUser user;

    /**
     * @param log
     * @param arrivedAt
     * @param user
     * @param text
     * @param isP2pChannel
     */
    public IrcMessage(IrcLog log, OffsetDateTime arrivedAt, IrcUser user, String text, boolean isP2pChannel, IrcMessageType type) {
        this(log, arrivedAt, user, text, log.getChannel().getAccount().getAcceptedNick(), isP2pChannel, type);
    }

    public IrcMessage(IrcLog log, OffsetDateTime arrivedAt, IrcUser user, String text, String myNick,
            boolean isP2pChannel, IrcMessageType type) {
        super(log.getCharLength(), log.getLineIndex(), arrivedAt, user == null ? null : user.getNick(), text,
                user != null ? log.getChannel().getUserIndex(user.getNick()) : Colors.INVALID_INDEX, myNick,
                isP2pChannel, type);
        this.log = log;
        this.user = user;
    }

    /**
     * @return
     */
    public IrcLog getLog() {
        return log;
    }

    /**
     * @return
     */
    public IrcNotificationLevel getNotificationLevel() {
        if (this.notificationLevel == null) {
            this.notificationLevel = log.getChannel().getModel().getNotificationLevelProvider().getNotificationLevel(this);
        }
        return notificationLevel;
    }
    public IrcUser getUser() {
        return user;
    }

}

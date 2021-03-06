/*
 * Copyright (c) 2014 Peter Palaga.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.l2x6.eircc.core.model;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.l2x6.eircc.core.IrcException;
import org.l2x6.eircc.core.model.PlainIrcMessage.IrcMessageType;
import org.l2x6.eircc.core.model.event.IrcModelEvent;
import org.l2x6.eircc.core.model.event.IrcModelEvent.EventType;
import org.l2x6.eircc.core.model.resource.IrcLogResource;
import org.l2x6.eircc.core.util.BidiIterator;
import org.l2x6.eircc.core.util.IrcLogReader;
import org.l2x6.eircc.core.util.IrcLogReader.IrcLogReaderException;
import org.l2x6.eircc.ui.EirccUi;

/**
 * @author <a href="mailto:ppalaga@redhat.com">Peter Palaga</a>
 */
public class IrcLog extends IrcObject implements Iterable<IrcMessage> {

    public static final int NOTHING_SAVED = -1;
    private final AbstractIrcChannel channel;
    private int charLength = 0;
    private int lastChatMessageIndex = -1;
    /** The user has read all messages till (and including) this instant */
    private int lastReadIndex = -1;
    /** The time of the last non-system message that arrived */
    // private Instant lastMessageTime = Instant.MIN;
    private int lastSavedMessageIndex = IrcLog.NOTHING_SAVED;
    private int firstUpdatedMessageIndex = IrcLog.NOTHING_SAVED;
    private int lineIndex = 0;

    private boolean loading = false;
    private final IrcLogResource logResource;

    private final List<IrcMessage> messages = new ArrayList<IrcMessage>();
    private IrcNotificationLevel notificationLevel = IrcNotificationLevel.NO_NOTIFICATION;

    /**
     * @param id
     * @param channel
     * @param startedOn
     */
    public IrcLog(AbstractIrcChannel channel, IrcLogResource logResource) {
        super(channel.getModel(), channel.getLogsFolderPath());
        this.channel = channel;
        this.logResource = logResource;

        load();
    }

    /**
     *
     */
    public void allRead() {
        lastReadIndex = messages.size() - 1;
        setNotificationLevel(IrcNotificationLevel.NO_NOTIFICATION);
    }

    public void appendErrorMessage(String text) {
        IrcMessage m = new IrcMessage(this, OffsetDateTime.now(), null, text, channel.isP2p(), IrcMessageType.ERROR);
        appendMessage(m);
    }

    public void appendMessage(IrcMessage message) {
        appendMessage(message, true);
    }

    public void replaceOrAppendMessage(IrcMessageReplacer replacer, boolean fireEvent) {
        ListIterator<IrcMessage> it = messages.listIterator(messages.size());
        LOOP: while (it.hasPrevious()) {
            int index = it.previousIndex();
            IrcMessage m = it.previous();
            switch (replacer.match(m)) {
            case MATCH:
                replaceMessage(index, m, replacer, fireEvent);
                return;
            case CONTINUE:
                /* do nothing */
                break;
            case STOP:
                /* stop the iteration */
                break LOOP;
            }
        }
        appendMessage(replacer.createNewMessage(this), fireEvent);
    }

    /**
     * @param message
     * @param index
     * @param fireEvent
     */
    private void replaceMessage(int index, IrcMessage replacedMessage, IrcMessageReplacer replacer, boolean fireEvent) {
        List<IrcMessage> changedMessages = messages.subList(index, messages.size());
        int i = 0;
        this.charLength = replacedMessage.getRecordOffset();
        this.lineIndex = replacedMessage.getLineIndex();
        if (firstUpdatedMessageIndex == NOTHING_SAVED || firstUpdatedMessageIndex > index) {
            firstUpdatedMessageIndex = index;
        }
        IrcMessage replacement = replacer.createReplacementMessage(replacedMessage);
        messages.set(index, replacement);
        i++;
        for (; i < changedMessages.size(); i++) {
            IrcMessage m = changedMessages.get(i);
            IrcMessage fixedMessage = m.fixOffsets();
            messages.set(index + i, fixedMessage);
        }

        if (fireEvent) {
            channel.getAccount().getModel().fire(new IrcModelEvent(EventType.MESSAGE_REPLACED, replacement));
        }
    }

    private void appendMessage(IrcMessage message, boolean fireEvent) {
        messages.add(message);
        if (message.getType() == IrcMessageType.CHAT && !message.isFromMe()) {
            lastChatMessageIndex = messages.size() - 1;
        }
        charLength += message.getRecordLenght();
        lineIndex += message.getLineCount();
        if (fireEvent) {
            channel.getAccount().getModel().fire(new IrcModelEvent(EventType.NEW_MESSAGE, message));
        }
    }

    public void appendSystemMessage(String text) {
        appendSystemMessage(text, null);
    }

    /**
     * @param text
     * @param rawInput
     */
    public void appendSystemMessage(String text, String rawInput) {
        IrcMessage m = new IrcMessage(this, OffsetDateTime.now(), null, text, getChannel().getAccount()
                .getAcceptedNick(), channel.isP2p(), IrcMessageType.SYSTEM, rawInput);
        appendMessage(m);
    }

    /**
     * @see org.l2x6.eircc.core.model.IrcObject#dispose()
     */
    @Override
    public void dispose() {
    }

    public void ensureAllSaved(IProgressMonitor monitor) throws IrcException {
        Object lock = logResource.getLockObject();
        synchronized (lock) {
            if (lastSavedMessageIndex == messages.size() - 1 && firstUpdatedMessageIndex == NOTHING_SAVED) {
                return;
            }

            if (loading) {
                System.out.println("saving while loading");
            }

            IFileEditorInput editorInput = logResource.getEditorInput();
            IDocumentProvider documentProvider = getModel().getRootResource().getDocumentProvider();
            IDocument document = logResource.getDocument();

            try {
                documentProvider.aboutToChange(editorInput);

                if (lastSavedMessageIndex == IrcLog.NOTHING_SAVED) {
                    document.set("");
                }

                if (firstUpdatedMessageIndex != IrcLog.NOTHING_SAVED && firstUpdatedMessageIndex < lastSavedMessageIndex) {
                    lastSavedMessageIndex = firstUpdatedMessageIndex - 1;
                    /* truncate the document */
                    PlainIrcMessage m = messages.get(firstUpdatedMessageIndex);
                    int startTruncate = m.getRecordOffset();
                    document.replace(startTruncate, document.getLength() - startTruncate, "");
                    firstUpdatedMessageIndex = IrcLog.NOTHING_SAVED;
                }

                /* append unsaved messages */
                for (int i = lastSavedMessageIndex + 1; i < messages.size(); i++) {
                    PlainIrcMessage m = messages.get(i);
                    m.write(document);
                    // out.flush();
                }
                documentProvider.saveDocument(monitor, editorInput, document, true);
                lastSavedMessageIndex = messages.size() - 1;
            } catch (CoreException e) {
                throw new IrcException("Could not perform ensureAllSaved() for "+ logResource.getLogFile(), e, this);
            } catch (BadLocationException e) {
                throw new IrcException("Could not perform ensureAllSaved() for "+ logResource.getLogFile(), e, this);
            } finally {
                documentProvider.changed(editorInput);
                monitor.done();
            }
        }
    }

    public AbstractIrcChannel getChannel() {
        return channel;
    }

    int getCharLength() {
        return charLength;
    }

    public IrcMessage getHottestMessage() {
        IrcMessage result = null;
        boolean hasUnreadMessages = !messages.isEmpty() && lastReadIndex < lastChatMessageIndex;
        if (hasUnreadMessages) {
            ListIterator<IrcMessage> it = messages.listIterator(messages.size());
            while (it.hasPrevious()) {
                int i = it.previousIndex();
                if (lastReadIndex >= i) {
                    break;
                }
                IrcMessage m = it.previous();
                if (m.getType() == IrcMessageType.CHAT) {
                    IrcNotificationLevel level = m.getNotificationLevel();
                    if (result == null || level.getLevel() > result.getNotificationLevel().getLevel()) {
                        result = m;
                        if (result.getNotificationLevel() == IrcNotificationLevel.ME_NAMED) {
                            /* higher level is not possible */
                            return m;
                        }
                    }
                }
            }
        }
        return result;
    }

    int getLineIndex() {
        return lineIndex;
    }

    public IrcLogResource getLogResource() {
        return logResource;
    }

    public int getMessageCount() {
        return messages.size();
    }

    public IrcNotificationLevel getNotificationLevel() {
        return notificationLevel;
    }

    public OffsetDateTime getStartedOn() {
        return logResource.getTime();
    }

    /**
     * @see java.lang.Iterable#iterator()
     */
    @Override
    public Iterator<IrcMessage> iterator() {
        return listIterator();
    }

    public BidiIterator<IrcMessage> listIterator() {
        return new BidiIterator<IrcMessage>(messages.listIterator());
    }

    public BidiIterator<IrcMessage> listIterator(int index) {
        return new BidiIterator<IrcMessage>(messages.listIterator(index));
    }

    /**
     *
     */
    private void load() {
        Object lock = logResource.getLockObject();
        synchronized (lock) {
            loading = true;
            IFileEditorInput editorInput = logResource.getEditorInput();
            System.out.println("loading " + editorInput.getFile());
            IrcLogReader reader = null;
            try {
                IDocument document = logResource.getDocument();
                if (document.getLength() > 0) {
                    reader = new IrcLogReader(document, editorInput.getFile().toString(), logResource
                            .getChannelResource().isP2p());
                    while (reader.hasNext()) {
                        PlainIrcMessage message = reader.next();
                        appendMessage(message.toIrcMessage(this), false);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (IrcLogReaderException e) {
                EirccUi.log(e);
            } finally {
                try {
                    if (reader != null) {
                        reader.close();
                    }
                } catch (IOException e) {
                    EirccUi.log(e);
                }
            }
            lastSavedMessageIndex = messages.size() - 1;
            loading = false;
            allRead();
        }
    }

    public void setNotificationLevel(IrcNotificationLevel state) {
        IrcNotificationLevel oldState = this.notificationLevel;
        this.notificationLevel = state;
        if (oldState != state) {
            channel.getAccount().getModel().fire(new IrcModelEvent(EventType.LOG_STATE_CHANGED, this));
        }
    }

    public void updateNotificationLevel() {
        IrcMessage hottestMessage = getHottestMessage();
        IrcNotificationLevel newLevel = hottestMessage != null ? hottestMessage.getNotificationLevel()
                : IrcNotificationLevel.NO_NOTIFICATION;
        setNotificationLevel(newLevel);
        return;
    }

}

package pl.myproject.kanbanproject2.messaging;

public interface PushSender {

    PushKind kind();

    boolean configured();

    void send(PushEndpoint endpoint, long conversationId, long seq) throws PushException;

    class PushException extends Exception {

        private final boolean gone;

        public PushException(String message, Throwable cause, boolean gone) {
            super(message, cause);
            this.gone = gone;
        }

        public boolean isGone() {
            return gone;
        }
    }
}

package pl.myproject.kanbanproject2.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.user.AccountDeleting;

@Component
@RequiredArgsConstructor
public class ConversationAccounts {

    private final ConversationService conversations;

    @EventListener
    public void on(AccountDeleting deleting) {
        conversations.forgetAccount(deleting.user());
    }
}

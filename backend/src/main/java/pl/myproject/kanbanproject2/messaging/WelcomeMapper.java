package pl.myproject.kanbanproject2.messaging;

import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
public class WelcomeMapper implements Function<MlsWelcome, WelcomeDto> {

    @Override
    public WelcomeDto apply(MlsWelcome welcome) {
        return new WelcomeDto(welcome.getId(), welcome.getConversation().getId(), welcome.getSeq(), welcome.getBody());
    }
}

package pl.myproject.kanbanproject2.messaging;

import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
public class KeyLogEntryMapper implements Function<KeyLogEntry, KeyLogEntryDto> {

    @Override
    public KeyLogEntryDto apply(KeyLogEntry entry) {
        return new KeyLogEntryDto(entry.getId(), entry.getChange(), entry.getSubject(), entry.getPrevHash(),
                entry.getEntryHash(), entry.getAt());
    }
}

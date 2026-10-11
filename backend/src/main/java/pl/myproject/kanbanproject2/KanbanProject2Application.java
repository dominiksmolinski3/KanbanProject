package pl.myproject.kanbanproject2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import pl.myproject.kanbanproject2.config.DatabaseMigration;

@SpringBootApplication
@EnableScheduling
public class KanbanProject2Application {

    public static void main(String[] args) {
        if (DatabaseMigration.requested(args)) {
            DatabaseMigration.run(args);
            return;
        }

        SpringApplication.run(KanbanProject2Application.class, args);
    }
}

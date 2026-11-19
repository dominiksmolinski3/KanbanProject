package pl.myproject.kanbanproject2.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IdentityKeyRepository extends JpaRepository<IdentityKey, Integer> {
}

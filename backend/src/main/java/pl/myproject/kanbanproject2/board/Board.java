package pl.myproject.kanbanproject2.board;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import pl.myproject.kanbanproject2.layout.column.Column;
import pl.myproject.kanbanproject2.layout.row.Row;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.user.User;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@NoArgsConstructor
@Setter
@Getter
@Entity
@Table(name = "boards")
public class Board {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @jakarta.persistence.Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @jakarta.persistence.Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "board_members",
            joinColumns = @JoinColumn(name = "board_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    @BatchSize(size = 50)
    private Set<User> members = new LinkedHashSet<>();

    @OneToMany(mappedBy = "board", cascade = CascadeType.ALL)
    private List<Column> columns;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flow_start_column_id")
    private Column flowStartColumn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flow_done_column_id")
    private Column flowDoneColumn;

    @OneToMany(mappedBy = "board", cascade = CascadeType.ALL)
    private List<Row> rows;

    @OneToMany(mappedBy = "board", cascade = CascadeType.ALL)
    private List<Task> tasks;

    public Board(String name, User owner) {
        this.name = name;
        this.owner = owner;
        if (owner != null) {
            this.members = new LinkedHashSet<>(Set.of(owner));
        }
    }

    public void forgetFlowColumn(Column column) {
        if (column == null || column.getId() == null) {
            return;
        }
        if (flowStartColumn != null && column.getId().equals(flowStartColumn.getId())) {
            flowStartColumn = null;
        }
        if (flowDoneColumn != null && column.getId().equals(flowDoneColumn.getId())) {
            flowDoneColumn = null;
        }
    }

    public boolean isOwnedBy(User user) {
        return user != null && owner != null && owner.getId() != null
                && owner.getId().equals(user.getId());
    }

    public boolean isVisibleTo(User user) {
        if (user == null || user.getId() == null) {
            return false;
        }
        if (isOwnedBy(user)) {
            return true;
        }
        return members != null && members.stream()
                .anyMatch(member -> user.getId().equals(member.getId()));
    }

    public boolean isWritableBy(User user, BoardRole role) {
        if (!isVisibleTo(user)) {
            return false;
        }
        if (isOwnedBy(user)) {
            return true;
        }
        return role != BoardRole.VIEWER;
    }

    public void addMember(User user) {
        if (members == null) {
            members = new LinkedHashSet<>();
        }
        if (!isVisibleTo(user)) {
            members.add(user);
        }
    }

    public void removeMember(User user) {
        if (members != null) {
            members.removeIf(member -> member.getId().equals(user.getId()));
        }
    }

    public Collection<User> everyone() {
        var all = new LinkedHashMap<Integer, User>();
        if (owner != null) {
            all.put(owner.getId(), owner);
        }
        if (members != null) {
            members.forEach(member -> all.putIfAbsent(member.getId(), member));
        }
        return all.values();
    }
}

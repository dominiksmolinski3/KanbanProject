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

/**
 * A board and the people allowed to see it.
 *
 * <p>This is the unit of tenancy: every column, row and task belongs to exactly one board, and
 * being a member of that board is the only thing that grants access to any of them. Before this
 * existed, "authenticated" was the whole authorization model — one shared board that every account
 * on the deployment could read, rewrite and delete.
 *
 * <p>Two levels, deliberately, rather than a role table: <em>owner</em>, who may rename the board,
 * delete it and change who is on it, and <em>member</em>, who may do anything to its contents. A
 * finer model is easy to add on top of this one and impossible to add on top of nothing.
 *
 * <p>The owner is nullable for exactly one row: the board the V5 migration creates to hold data
 * that predates boards. Nothing owns that data, and inventing an owner for it in SQL would be a
 * guess; instead the first account to open a board adopts it. See {@link BoardService#provisionFor}.
 */
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

    /*
     * Owning side, and the only mapping of the membership. User has no inverse collection: the
     * question asked in practice is "which boards can this caller see", which is a repository
     * query, and a bidirectional mapping would be a second copy of the same fact to keep in sync.
     */
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

    /** True when {@code user} owns this board. A null owner is owned by nobody, not by everybody. */
    public boolean isOwnedBy(User user) {
        return user != null && owner != null && owner.getId() != null
                && owner.getId().equals(user.getId());
    }

    /**
     * True when {@code user} may see this board's contents.
     *
     * <p>Compared on id rather than on the entity, because the caller comes from the JWT filter and
     * the members come from the current persistence context: two {@link User} instances for the
     * same account are not {@code equals} unless the entity says so, and it does not.
     */
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

    /** Adds a member, tolerating the lazily-initialised collection being absent. */
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

    /**
     * Members including the owner, who is a member whether or not the join table says so.
     *
     * <p>Keyed on id rather than collected into a {@code Set<User>}, for the same reason
     * {@link #isVisibleTo} compares ids: {@link User} inherits identity equality, so the owner and
     * the owner's row in the member list are two objects as far as a {@code HashSet} is concerned,
     * and the caller would be listed twice.
     */
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

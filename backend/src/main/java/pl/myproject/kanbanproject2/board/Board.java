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
 * A board and the people allowed to see it — the unit of tenancy: every column, row and task
 * belongs to exactly one board, and membership is the only thing that grants access to any of them.
 *
 * <p><em>Owner</em> may rename, delete and change membership - tracked separately via
 * {@code boards.owner_id} and unrelated to the role below. Everyone else on the member list carries
 * a {@link BoardRole} ({@code board_members.role}, added in {@code V21}): <em>member</em> may do
 * anything to the board's contents, <em>viewer</em> may see it and nothing else. The owner is
 * nullable only for the one board V5 creates to hold data that predates boards; the first account to
 * open a board adopts it (see {@link BoardService#provisionFor}).
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
     * Owning side, and the only mapping of the membership. User has no inverse collection: a
     * bidirectional mapping would just be a second copy of the same fact to keep in sync.
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

    /*
     * The board's own definition of when work starts and when it is done, for the flow screen
     * (FLOW-02, V23). Null keeps FEAT-07's rule - the last column is done, and a card starts when it
     * arrives on the board - so an unset board reads exactly as it did. A setting, not content: the
     * owner sets it, as they set the name, and deleting the chosen column puts the board back on the
     * default (ON DELETE SET NULL, and ColumnService clears it in Java first).
     */
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

    /**
     * Puts the flow definition back on the default wherever it names {@code column}, which is about
     * to be deleted. Compared on id, for the reason {@link #isVisibleTo} is.
     */
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

    /**
     * True when {@code user} may change this board's contents, given {@code role} - the caller's own
     * role on it, resolved by {@link BoardService} from {@code board_members.role} since this entity
     * carries no per-member data of its own. The owner may always write, whatever the stored role
     * says; a {@link BoardRole#VIEWER} may see the board ({@link #isVisibleTo}) and nothing else.
     */
    public boolean isWritableBy(User user, BoardRole role) {
        if (!isVisibleTo(user)) {
            return false;
        }
        if (isOwnedBy(user)) {
            return true;
        }
        return role != BoardRole.VIEWER;
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

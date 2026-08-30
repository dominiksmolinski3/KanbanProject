package pl.myproject.kanbanproject2.file;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import pl.myproject.kanbanproject2.user.User;

@Entity
@Table(name = "files")
@Getter
@Setter
public class File {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String type;

    @Lob // Stores the file as bytes
    private byte[] data;

    /*
     * Who uploaded it, which is the only thing that decides who may read or delete it. Nullable
     * for the rows that predate this column: the V5 migration can recover the owner of an avatar
     * from users.avatar_id and has nothing to go on for anything else, so an unowned file is
     * treated as belonging to nobody rather than to everybody - see FileService.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    public File() {
    }

    public File(String name, String type, byte[] data) {
        this.name = name;
        this.type = type;
        this.data = data;
    }

    public File(String name, String type, byte[] data, User owner) {
        this(name, type, data);
        this.owner = owner;
    }

    /** A file with no owner is readable by nobody, which is the safe reading of a null. */
    public boolean isOwnedBy(User user) {
        return user != null && owner != null && owner.getId() != null
                && owner.getId().equals(user.getId());
    }


}

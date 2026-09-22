package pl.myproject.kanbanproject2.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.persistence.Column;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import pl.myproject.kanbanproject2.task.Task;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@NoArgsConstructor
@AllArgsConstructor
@Setter
@Getter
@Entity
@Table(name = "users")
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    @Column(unique = true)
    private String email;
    private String password;
    private String name;
    // Unboxed straight into isEnabled() below, which Spring Security calls on every
    // authentication: a nullable column here meant one bad row threw an NPE mid-login.
    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;
    @Column(name = "verification_code")
    private String verificationCode;
    @Column(name = "verification_expiration")
    private LocalDateTime verificationCodeExpiresAt;
    /*
     * A second pair rather than a reuse of the verification code above, since sharing one field
     * would let a pending reset cancel a pending verification. Stored hashed, unlike the
     * verification code, because it is a credential: anyone reading the users table could otherwise
     * reset any account at will.
     */
    @Column(name = "password_reset_code")
    private String passwordResetCode;
    @Column(name = "password_reset_expiration")
    private LocalDateTime passwordResetExpiresAt;
    private Integer wipLimit;
    /*
     * The language this account is mailed in. Not nullable, since a message has to be written in
     * something. Guessed at signup and explicitly set after that; not read for anything on screen,
     * since the client picks its own language, and exists for the two moments it is not there - the
     * verification mail and the deadline sweep.
     */
    @Column(name = "locale", nullable = false, length = 8)
    private String locale = SupportedLocales.DEFAULT;
    @ManyToMany(mappedBy = "users")
    @JsonIgnore
    private Set<Task> tasks = new HashSet<>();
    /*
     * The avatar's bytes live in Azure Blob Storage (pl.myproject.kanbanproject2.user.avatar), the
     * same move task attachments made in V12 and for the same reason: a @Lob put every upload into
     * Postgres's own storage, backups and restore window for data no query ever looked inside. One
     * user has at most one avatar, so this is four columns rather than a second table with a foreign
     * key back - the shape task_attachments needs because a task holds many. All four are null
     * together when there is no avatar; avatarBlobName is opaque (avatars/<id>/<uuid>), and nothing
     * a person typed is in it, the same rule task_attachments.blobName follows.
     */
    @Column(name = "avatar_blob_name", length = 200)
    private String avatarBlobName;
    @Column(name = "avatar_content_type")
    private String avatarContentType;
    @Column(name = "avatar_size_bytes")
    private Long avatarSizeBytes;
    @Column(name = "avatar_uploaded_at")
    private Instant avatarUploadedAt;


    public User(String name, String email, String password) {
        this.name = name;
        this.email = email;
        this.password = password;
    }


    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }


    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
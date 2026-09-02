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
import pl.myproject.kanbanproject2.file.File;
import pl.myproject.kanbanproject2.task.Task;

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
     * Deliberately a second pair rather than a reuse of the verification code above. They answer
     * different questions - "is this address real" and "does this person still control it" - and
     * sharing one field would mean a pending reset silently cancelled a pending verification, or
     * that a code mailed for one purpose was accepted for the other. The reset code is stored
     * hashed, because unlike the verification code it is a credential: anyone who can read the
     * users table could otherwise reset any account at will.
     */
    @Column(name = "password_reset_code")
    private String passwordResetCode;
    @Column(name = "password_reset_expiration")
    private LocalDateTime passwordResetExpiresAt;
    private Integer wipLimit;
    /*
     * The language this account is mailed in. Not nullable, because a message has to be written in
     * something and a null here would make every call site choose - which is three places choosing
     * independently, and the shape the wording itself was in before MailTemplates.
     *
     * A guess at signup, from the browser that happened to be used, and an explicit setting after
     * that. It is not read for anything on screen: the client picks its own language from the
     * browser and its own store, and this exists for the two moments the client is not there -
     * the verification mail and the deadline sweep.
     */
    @Column(name = "locale", nullable = false, length = 8)
    private String locale = SupportedLocales.DEFAULT;
    @ManyToMany(mappedBy = "users")
    @JsonIgnore
    private Set<Task> tasks = new HashSet<>();
    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "avatar_id")
    private File avatar;


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
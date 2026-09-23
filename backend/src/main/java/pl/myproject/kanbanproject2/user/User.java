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
    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;
    @Column(name = "verification_code")
    private String verificationCode;
    @Column(name = "verification_expiration")
    private LocalDateTime verificationCodeExpiresAt;
    @Column(name = "password_reset_code")
    private String passwordResetCode;
    @Column(name = "password_reset_expiration")
    private LocalDateTime passwordResetExpiresAt;
    private Integer wipLimit;
    @Column(name = "locale", nullable = false, length = 8)
    private String locale = SupportedLocales.DEFAULT;
    @ManyToMany(mappedBy = "users")
    @JsonIgnore
    private Set<Task> tasks = new HashSet<>();
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
package io.github.astatine202.scras.backend.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;
import jakarta.persistence.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "t_user_info")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, length = 100)
    private String password;

    @Column(unique = true, length = 100)
    private String email;

    @Column(nullable = false)
    private Integer role = 0;

    @Column(nullable = false)
    private Integer task_count = 0;

    @Column(nullable = false, updatable = false)
    private LocalDateTime create_at = LocalDateTime.now();
}

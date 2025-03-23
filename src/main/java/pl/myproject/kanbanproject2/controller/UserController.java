package pl.myproject.kanbanproject2.controller;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import pl.myproject.kanbanproject2.dto.UserDTO;
import pl.myproject.kanbanproject2.model.User;
import pl.myproject.kanbanproject2.service.UserService;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/users")
public class UserController {
    private final UserService userService;

    @Autowired
    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping()
    public ResponseEntity<List<UserDTO>> getAllUsers(){
        List<UserDTO> users = userService.getAllUsers();
        return ResponseEntity.ok(users);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserDTO> getUserById(@PathVariable Integer id){
        try {
            UserDTO user = userService.getUserById(id);
            return ResponseEntity.ok(user);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public ResponseEntity<User> addUser(@RequestBody User user) {
        return ResponseEntity.ok(userService.addUser(user));
    }


    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Integer id){
        try {
            userService.deleteUser(id);
            return ResponseEntity.noContent().build();
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<User> updateUser(@PathVariable Integer id, @RequestBody User user){
        try {
            User updatedUser = userService.updateUser(id, user);
            return ResponseEntity.ok(updatedUser);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<UserDTO> patchUser(@PathVariable Integer id, @RequestBody UserDTO userDTO){
        try {
            UserDTO patchedUser = userService.patchUser(userDTO, id);
            return ResponseEntity.ok(patchedUser);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // Upload awatara
    @PostMapping("/{id}/avatar")
    public ResponseEntity<String> uploadAvatar(@PathVariable Integer id, @RequestParam("file") MultipartFile file) {
        try {
            userService.uploadAvatar(id, file);
            return ResponseEntity.ok("Avatar uploaded successfully!");
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // Pobieranie awatara
    @GetMapping("/{id}/avatar")
    public ResponseEntity<byte[]> getAvatar(@PathVariable Integer id) {
        try {
            byte[] avatarData = userService.getAvatar(id);
            String contentType = userService.getAvatarContentType(id);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .body(avatarData);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    
    @DeleteMapping("/{id}/avatar")
    public ResponseEntity<String> deleteAvatar(@PathVariable Integer id) {
        try {
            userService.deleteAvatar(id);
            return ResponseEntity.ok("Avatar deleted successfully!");
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PatchMapping("/{id}/wip-limit")
    public ResponseEntity<UserDTO> updateWipLimit(@PathVariable Integer id, @RequestBody Integer wipLimit) {
        UserDTO updatedUser = userService.updateWipLimit(id, wipLimit);
        return ResponseEntity.ok(updatedUser);
    }

    @GetMapping("/{id}/wip-status")
    public ResponseEntity<Boolean> checkWipStatus(@PathVariable Integer id) {
        try {
            boolean isWithinLimit = userService.checkWipStatus(id);
            return ResponseEntity.ok(isWithinLimit);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

}

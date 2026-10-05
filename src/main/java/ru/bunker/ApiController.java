package ru.bunker;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final RoomService rooms;

    public ApiController(RoomService rooms) {
        this.rooms = rooms;
    }

    private static String token(String auth) {
        return auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : null;
    }

    @GetMapping("/rules/classic")
    public RulePack classic() {
        return RulePack.classic();
    }

    @PostMapping("/packs/validate")
    public Map<String, Object> validate(@RequestBody RulePack pack) {
        RulePack.require(!"classic".equals(pack.id()), "Для личного набора используйте собственный ID.");
        pack.validate(false, 0);
        return Map.of("valid", true);
    }

    @PostMapping("/rooms")
    public RoomService.Ticket create(@RequestBody CreateRoom request) {
        return rooms.create(request.name, request.rulesetId, request.pack);
    }

    @PostMapping("/rooms/{code}/join")
    public RoomService.Ticket join(@PathVariable String code, @RequestBody JoinRoom request) {
        return rooms.join(code, request.name);
    }

    @GetMapping("/rooms/{code}")
    public Map<String, Object> view(@PathVariable String code, @RequestHeader(value = "Authorization", required = false) String auth) {
        return rooms.view(code, token(auth));
    }

    @PostMapping("/rooms/{code}/actions")
    public Map<String, Object> act(@PathVariable String code,
                                   @RequestHeader(value = "Authorization", required = false) String auth, @RequestBody RoomService.Action action) {
        return rooms.act(code, token(auth), action);
    }

    @ExceptionHandler(GameException.class)
    public ResponseEntity<Map<String, String>> gameError(GameException e) {
        return ResponseEntity.status(e.status()).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> jsonError() {
        return ResponseEntity.badRequest().body(Map.of("message", "Некорректный JSON или неизвестные поля."));
    }

    public record CreateRoom(String name, String rulesetId, RulePack pack) {
    }

    public record JoinRoom(String name) {
    }
}

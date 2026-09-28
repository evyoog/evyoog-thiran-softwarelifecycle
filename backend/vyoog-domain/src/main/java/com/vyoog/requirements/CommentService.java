package com.vyoog.requirements;

import com.vyoog.identity.AppUser;
import com.vyoog.identity.AppUserRepository;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** VYB-0124: comments, with @mentions resolved to real users at write time. */
@Service
public class CommentService {

    // @word, or @"multi word name" for a display name with spaces.
    private static final Pattern MENTION = Pattern.compile("@(\"[^\"]+\"|[\\w.+-]+)");

    private final RequirementCommentRepository comments;
    private final RequirementRepository requirements;
    private final AppUserRepository users;

    public CommentService(RequirementCommentRepository comments, RequirementRepository requirements,
                           AppUserRepository users) {
        this.comments = comments;
        this.requirements = requirements;
        this.users = users;
    }

    public record CommentWithMentions(RequirementComment comment, List<UUID> mentionedUserIds) {}

    @Transactional
    public CommentWithMentions add(UUID requirementId, UUID authorId, String body) {
        if (!requirements.existsById(requirementId)) {
            throw new NoSuchElementException("No such requirement: " + requirementId);
        }
        RequirementComment saved = comments.save(new RequirementComment(requirementId, authorId, body));
        return new CommentWithMentions(saved, resolveMentions(body));
    }

    public List<RequirementComment> list(UUID requirementId) {
        return comments.findAllByRequirementIdOrderByCreatedAtAsc(requirementId);
    }

    /**
     * VYB-0124 AC2: mentions resolve to users. Tries the token as an email local-part
     * first, then as a display name — unresolvable mentions are just dropped rather
     * than erroring, since a typo in a comment shouldn't block posting it.
     */
    private List<UUID> resolveMentions(String body) {
        List<UUID> resolved = new java.util.ArrayList<>();
        Matcher m = MENTION.matcher(body);
        while (m.find()) {
            String token = m.group(1).replace("\"", "");
            findUser(token).ifPresent(u -> resolved.add(u.getId()));
        }
        return resolved;
    }

    private Optional<AppUser> findUser(String token) {
        // stream().findFirst() rather than a single-result finder: the directory allows
        // two rows to share an email, and a single-result finder throws on that instead
        // of resolving the mention. An @mention is a convenience — it resolves to one of
        // them or, failing that, falls through to the name match below.
        Optional<AppUser> byEmail = users.findAllByEmailIgnoreCase(token).stream().findFirst()
            .or(() -> users.findAll().stream() // small table; fine to scan for a local-part match
                .filter(u -> u.getEmail() != null && u.getEmail().split("@")[0].equalsIgnoreCase(token))
                .findFirst());
        if (byEmail.isPresent()) return byEmail;
        List<AppUser> byName = users.findAllByDisplayNameIgnoreCase(token.replace('.', ' '));
        return byName.isEmpty() ? Optional.empty() : Optional.of(byName.get(0));
    }
}

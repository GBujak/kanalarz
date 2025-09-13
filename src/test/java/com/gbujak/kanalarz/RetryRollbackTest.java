package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Rollback;
import com.gbujak.kanalarz.annotations.RollforwardOut;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@Service
class RetryRollbackTestService {

    public static RuntimeException error = new RuntimeException();
    private final Map<UUID, String> posts = new ConcurrentHashMap<>();

    public UUID createPost(String post) {
        var id = UUID.randomUUID();
        posts.put(id, post);
        return id;
    }

    public void deletePost(UUID postId) {
        if (Math.random() < .8) {
            throw error;
        }
        posts.remove(postId);
    }

    public Map<UUID, String> getPosts() {
        return Map.copyOf(posts);
    }
}

@Component
@StepsHolder(identifier = "retry-rollback-test-steps")
class RetryRollbackTestSteps {

    @Autowired private RetryRollbackTestService service;

    @Step(identifier = "create-post")
    public UUID createPost(String post) {
        return service.createPost(post);
    }

    @Rollback(forStep = "create-post")
    public void rollbackCreatePost(@RollforwardOut UUID postId) {
        service.deletePost(postId);
    }
}

@SpringBootTest
public class RetryRollbackTest {

    @Autowired private Kanalarz kanalarz;
    @Autowired private RetryRollbackTestSteps steps;
    @Autowired private RetryRollbackTestService service;

    @Test
    void test() {
        var exception = new RuntimeException();
        var contextId = UUID.randomUUID();

        try {
            kanalarz.newContext().resumes(contextId).consume(ctx -> {
                for (int i = 0; i < 200; i++) {
                    steps.createPost("post-" + i);
                }
                throw exception;
            });
        } catch (KanalarzException.KanalarzRollbackStepFailedException e) {
            assertThat(e.getInitialStepFailedException()).isEqualTo(exception);
            assertThat(e.getRollbackStepFailedException()).isEqualTo(RetryRollbackTestService.error);
        }

        int failed = 0;
        while (true) {
            try {
                kanalarz.newContext()
                    .resumes(contextId)
                    .option(Kanalarz.Option.RETRY_FAILED_ROLLBACKS)
                    .rollbackNow();

                break;
            } catch (KanalarzException.KanalarzRollbackStepFailedException e) {
                failed++;
                assertThat(e.getInitialStepFailedException()).isNull();
                assertThat(e.getRollbackStepFailedException()).isEqualTo(RetryRollbackTestService.error);
            }
        }

        assertThat(failed).isGreaterThan(0);
        assertThat(service.getPosts()).isEmpty();
    }
}

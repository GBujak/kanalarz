package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Rollback;
import com.gbujak.kanalarz.annotations.RollforwardOut;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.*;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@NullMarked
@Service
class NestedContextsTestsService {

    public static final RuntimeException ERR = new RuntimeException();

    private final Map<UUID, String> postsA = Collections.synchronizedMap(new HashMap<>());
    private final Map<UUID, String> postsB = Collections.synchronizedMap(new HashMap<>());

    @Nullable
    String getPostA(UUID id) {
        return postsA.get(id);
    }

    UUID submitPostA(String post) {
        var id = UUID.randomUUID();
        postsA.put(id, post);
        return id;
    }

    void removePostA(UUID id) {
        if (postsA.remove(id) == null) {
            throw ERR;
        }
    }

    @Nullable
    String getPostB(UUID id) {
        return postsB.get(id);
    }

    UUID submitPostB(String post) {
        var id = UUID.randomUUID();
        postsB.put(id, post);
        return id;
    }

    void removePostB(UUID id) {
        if (postsB.remove(id) == null) {
            throw ERR;
        }
    }

    void clear() {
        postsA.clear();
        postsB.clear();
    }
}

@NullMarked
@Component
@StepsHolder("nested-contexts-test-steps")
class NestedContextsTestsSteps {

    @Autowired private NestedContextsTestsService service;

    @Step("submit-post-a")
    public UUID submitPostA(String post) {
        return service.submitPostA(post);
    }

    @Rollback("submit-post-a")
    public void rollbackSubmitPostA(@RollforwardOut UUID id) {
        service.removePostA(id);
    }

    @Step("submit-post-b")
    public UUID submitPostB(String post) {
        return service.submitPostB(post);
    }

    @Rollback("submit-post-b")
    public void rollbackSubmitPostB(@RollforwardOut UUID id) {
        service.removePostB(id);
    }
}

@NullMarked
@SpringBootTest
public class NestedContextsTests {

    @Autowired private NestedContextsTestsService service;
    @Autowired private NestedContextsTestsSteps steps;
    @Autowired private Kanalarz kanalarz;

    @BeforeEach
    void beforeEach() {
        service.clear();
    }

    @Test
    void test() {
        List<UUID> postIdsA = new ArrayList<>();
        List<UUID> postIdsB = new ArrayList<>();
        kanalarz.newContext().consume((ignored) -> {
            postIdsA.add(steps.submitPostA("test 1"));
            assertThatThrownBy(() ->
                kanalarz.newContext().consume((ignored2) -> {
                    postIdsB.add(steps.submitPostB("test 1"));
                    postIdsB.add(steps.submitPostB("test 2"));
                    throw new RuntimeException();
                })
            );
            postIdsA.add(steps.submitPostA("test 2"));
        });
        assertThat(service.getPostA(postIdsA.get(0))).isEqualTo("test 1");
        assertThat(service.getPostA(postIdsA.get(1))).isEqualTo("test 2");
        assertThat(service.getPostB(postIdsB.get(0))).isNull();
        assertThat(service.getPostB(postIdsB.get(1))).isNull();
    }
}

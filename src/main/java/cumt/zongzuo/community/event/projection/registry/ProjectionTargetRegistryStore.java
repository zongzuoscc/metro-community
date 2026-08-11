package cumt.zongzuo.community.event.projection.registry;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class ProjectionTargetRegistryStore {

    private final ProjectionTargetRegistryMapper mapper;

    ProjectionTargetRegistryStore(ProjectionTargetRegistryMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void registerSchemaOnly(List<ProjectionTargetRegistration> targets) {
        Objects.requireNonNull(targets, "targets");
        if (targets.isEmpty() || targets.stream().map(ProjectionTargetRegistration::id).distinct().count()
                != targets.size()) {
            throw new IllegalArgumentException("projection targets must be non-empty with unique ids");
        }
        for (ProjectionTargetRegistration target : targets) {
            mapper.insertSchemaOnlyIfAbsent(Objects.requireNonNull(target, "target"));
            if (mapper.countExactSchemaOnly(target) != 1) {
                throw new IllegalStateException("projection target registry drift: " + target.id());
            }
        }
    }
}

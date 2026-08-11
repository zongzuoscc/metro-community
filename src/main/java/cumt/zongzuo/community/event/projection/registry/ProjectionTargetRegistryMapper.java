package cumt.zongzuo.community.event.projection.registry;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
interface ProjectionTargetRegistryMapper {

    @Insert("""
            INSERT IGNORE INTO projection_target_registry
              (id,kind,consumer_name,physical_name,read_alias,schema_fingerprint,
               model_name,model_digest,dimension,generation,target_role,state,
               required_for_retention,rebuild_job_id,rollback_deadline,lock_version,
               operator_identity,created_at,updated_at)
            VALUES
              (#{target.id},#{target.kind},NULL,#{target.physicalName},#{target.readAlias},
               #{target.schemaFingerprint},#{target.modelName},#{target.modelDigest},
               #{target.dimension},#{target.generation},#{target.targetRole},'SCHEMA_ONLY',
               0,NULL,NULL,0,#{target.operatorIdentity},CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
            """)
    int insertSchemaOnlyIfAbsent(@Param("target") ProjectionTargetRegistration target);

    @Select("""
            SELECT COUNT(*)
            FROM projection_target_registry
            WHERE id=#{target.id} AND kind=#{target.kind} AND consumer_name IS NULL
              AND physical_name=#{target.physicalName} AND read_alias=#{target.readAlias}
              AND schema_fingerprint=#{target.schemaFingerprint}
              AND model_name=#{target.modelName} AND model_digest=#{target.modelDigest}
              AND dimension=#{target.dimension} AND generation=#{target.generation}
              AND target_role=#{target.targetRole} AND state='SCHEMA_ONLY'
              AND required_for_retention=0 AND rebuild_job_id IS NULL
              AND rollback_deadline IS NULL AND lock_version=0
              AND operator_identity=#{target.operatorIdentity}
            """)
    int countExactSchemaOnly(@Param("target") ProjectionTargetRegistration target);
}

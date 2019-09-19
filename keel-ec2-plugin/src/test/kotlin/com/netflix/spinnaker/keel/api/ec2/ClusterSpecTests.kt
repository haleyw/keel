package com.netflix.spinnaker.keel.api.ec2

import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spinnaker.keel.api.ArtifactType.DEB
import com.netflix.spinnaker.keel.api.DeliveryArtifact
import com.netflix.spinnaker.keel.api.ec2.HealthCheckType.ELB
import com.netflix.spinnaker.keel.model.Moniker
import com.netflix.spinnaker.keel.serialization.configuredObjectMapper
import com.netflix.spinnaker.keel.serialization.configuredYamlMapper
import dev.minutest.TestContextBuilder
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import strikt.api.expect
import strikt.api.expectThat
import strikt.assertions.all
import strikt.assertions.contains
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import java.time.Duration

internal class ClusterSpecTests : JUnit5Minutests {

  fun tests() = rootContext<ClusterSpec> {
    context("a spec with multiple regions and minimal configuration") {
      fixture {
        ClusterSpec(
          moniker = Moniker(
            app = "fnord",
            stack = "test"
          ),
          imageProvider = ArtifactImageProvider(DeliveryArtifact("fnord", DEB)),
          locations = ClusterLocations(
            accountName = "test",
            regions = setOf(
              ClusterRegion(
                region = "us-east-1",
                subnet = "internal (vpc0)",
                availabilityZones = setOf("us-east-1a", "us-east-1b", "us-east-1c")
              ),
              ClusterRegion(
                region = "us-west-2",
                subnet = "internal (vpc0)",
                availabilityZones = setOf("us-west-2a", "us-west-2b", "us-west-2c")
              )
            )
          ),
          defaults = ClusterServerGroupSpec(
            launchConfiguration = ClusterLaunchConfigurationSpec(
              instanceType = "m5.large",
              ebsOptimized = true,
              iamRole = "fnordInstanceProfile",
              instanceMonitoring = false
            ),
            capacity = Capacity(2, 2, 2),
            dependencies = Dependencies(
              loadBalancerNames = setOf("fnord-internal"),
              securityGroupNames = setOf("fnord", "fnord-elb")
            ),
            health = ClusterHealthSpec(
              warmup = Duration.ofSeconds(120)
            )
          ),
          overrides = mapOf(
            "us-east-1" to ClusterServerGroupSpec(
              launchConfiguration = ClusterLaunchConfigurationSpec(
                iamRole = "fnordEastInstanceProfile",
                keyPair = "fnord-keypair-325719997469-us-east-1"
              ),
              capacity = Capacity(5, 5, 5),
              dependencies = Dependencies(
                loadBalancerNames = setOf("fnord-external"),
                securityGroupNames = setOf("fnord-ext")
              ),
              health = ClusterHealthSpec(
                healthCheckType = ELB
              )
            ),
            "us-west-2" to ClusterServerGroupSpec(
              launchConfiguration = ClusterLaunchConfigurationSpec(
                keyPair = "fnord-keypair-325719997469-us-west-2"
              )
            )
          )
        )
      }

      mapOf(
        "YAML" to configuredYamlMapper(),
        "JSON" to configuredObjectMapper()
      ).forEach { (format, mapper) ->
        test("can serialize and deserialize as $format") {
          val text = mapper.writeValueAsString(this)

          println(text)

          val tree = mapper.readValue<ClusterSpec>(text)
          expectThat(tree).isEqualTo(this)
        }
      }

      resolvedContext {
        test("there is one server group per region specified in the cluster") {
          expectThat(result).hasSize(spec.locations.regions.size)
        }

        test("non-overrideable cluster-wide configuration is applied to all server groups") {
          expectThat(result)
            .all {
              get { launchConfiguration.imageProvider }
                .isEqualTo(spec.imageProvider)
            }
        }

        test("configuration is applied from the cluster-wide defaults when not overridden in region") {
          expectThat(result)
            .all {
              get { launchConfiguration.instanceType }
                .isEqualTo(spec.defaults.launchConfiguration?.instanceType)
            }
        }

        test("configuration is applied from the region when not present in the cluster-wide defaults") {
          expectThat(usWestServerGroup)
            .get { launchConfiguration.keyPair }
            .isEqualTo(spec.overrides["us-west-2"]?.launchConfiguration?.keyPair)
        }

        test("cluster-wide default configuration can be overridden by regions") {
          expect {
            that(usEastServerGroup.launchConfiguration.iamRole)
              .isEqualTo(spec.overrides["us-east-1"]?.launchConfiguration?.iamRole)
            that(usWestServerGroup.launchConfiguration.iamRole)
              .isEqualTo(spec.defaults.launchConfiguration?.iamRole)
          }
        }

        test("override dependencies are merged with cluster-wide defaults") {
          expect {
            that(usEastServerGroup.dependencies.loadBalancerNames)
              .contains(spec.defaults.dependencies?.loadBalancerNames!!)
              .contains(spec.overrides["us-east-1"]?.dependencies?.loadBalancerNames!!)
            that(usEastServerGroup.dependencies.securityGroupNames)
              .contains(spec.defaults.dependencies?.securityGroupNames!!)
              .contains(spec.overrides["us-east-1"]?.dependencies?.securityGroupNames!!)
          }
        }

        test("override health settings are merged with cluster-wide defaults") {
          expect {
            that(usEastServerGroup.health.warmup)
              .isEqualTo(spec.defaults.health?.warmup)
            that(usEastServerGroup.health.healthCheckType)
              .isEqualTo(spec.overrides["us-east-1"]?.health?.healthCheckType)
          }
        }
      }
    }
  }
}

private data class ResolvedSpecFixture(
  val spec: ClusterSpec,
  val result: Set<ServerGroupSpec>
) {
  val usEastServerGroup
    get() = result.first { it.location.region == "us-east-1" }
  val usWestServerGroup
    get() = result.first { it.location.region == "us-west-2" }
}

private fun TestContextBuilder<*, ClusterSpec>.resolvedContext(
  builder: TestContextBuilder<ClusterSpec, ResolvedSpecFixture>.() -> Unit
) =
  derivedContext<ResolvedSpecFixture>("when resolved") {
    deriveFixture {
      ResolvedSpecFixture(this, resolve())
    }

    builder()
  }
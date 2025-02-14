package com.ebiznext.comet.job.ingest

import com.ebiznext.comet.TestHelper

class IngestJobSpec extends TestHelper {
  new WithSettings() {
    "All Ingest Config" should "be known and taken  into account" in {
      val rendered = LoadConfig.usage()
      val expected =
        """
          |Usage: starlake load | ingest domain schema paths [options]
          |
          |  domain  Domain name
          |  schema  Schema name
          |  paths   list of comma separated paths
          |  options arguments to be used as substitutions
          |""".stripMargin
      rendered.substring(rendered.indexOf("Usage:")).replaceAll("\\s", "") shouldEqual expected
        .replaceAll("\\s", "")
    }
  }
}

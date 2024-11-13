/*
 * Jooby https://jooby.io
 * Apache License Version 2.0 https://jooby.io/LICENSE.txt
 * Copyright 2014 Edgar Espina
 */
package issues.i3579;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import io.jooby.openapi.OpenAPIResult;
import io.jooby.openapi.OpenAPITest;

public class Issue3579 {

  @OpenAPITest(value = App3579.class, templateName = "issues/i3579/ascii-doc.yaml")
  public void shouldGenerateAsciiDoc(OpenAPIResult result) throws IOException {
    var adoc = result.toAsciiDoc();
    System.out.println(adoc);
    Files.write(Paths.get("target", "output.adoc"), adoc.getBytes(StandardCharsets.UTF_8));
  }
}

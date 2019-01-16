package tapir.docs.openapi

import tapir.openapi._
import tapir.{EndpointInput, _}

object EndpointToOpenAPIDocs {
  def toOpenAPI(title: String, version: String, es: Iterable[Endpoint[_, _, _]]): OpenAPI = {
    val es2 = es.map(nameAllPathCapturesInEndpoint)
    val withSchemaKeys = new WithSchemaKeys(ObjectSchemasForEndpoints(es2))

    val base = OpenAPI(
      info = Info(title, None, None, version),
      servers = None,
      paths = Map.empty,
      components = withSchemaKeys.components
    )

    es2.map(withSchemaKeys.pathItem).foldLeft(base) {
      case (current, (path, pathItem)) =>
        current.addPathItem(path, pathItem)
    }
  }

  private def nameAllPathCapturesInEndpoint(e: Endpoint[_, _, _]): Endpoint[_, _, _] = {
    val (input2, _) = new EndpointInputMapper[Int](
      {
        case (EndpointInput.PathCapture(codec, None, info), i) =>
          (EndpointInput.PathCapture(codec, Some(s"p$i"), info), i + 1)
      },
      PartialFunction.empty
    ).mapInput(e.input, 1)

    e.copy(input = input2)
  }
}

package sttp.tapir.static

import sttp.model.headers.{ETag, Range}
import sttp.model.{Header, HeaderNames, MediaType, StatusCode}
import sttp.monad.MonadError
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.{FileRange, _}

import java.io.InputStream
import java.time.Instant

/** Static content endpoints, including files and resources. */
trait TapirStaticContentEndpoints {
  // we can't use oneOfMapping and mapTo since they are macros, defined in the same compilation unit

  private val pathsWithoutDots: EndpointInput[List[String]] =
    paths.mapDecode(ps =>
      if (ps.exists(p => p == "" || p == "." || p == ".."))
        DecodeResult.Error(ps.mkString("/"), new RuntimeException(s"Incorrect path: $ps"))
      else DecodeResult.Value(ps)
    )(identity)

  private val ifNoneMatchHeader: EndpointIO[Option[List[ETag]]] =
    header[Option[String]](HeaderNames.IfNoneMatch).mapDecode[Option[List[ETag]]] {
      case None    => DecodeResult.Value(None)
      case Some(h) => DecodeResult.fromEitherString(h, ETag.parseList(h)).map(Some(_))
    }(_.map(es => ETag.toString(es)))

  private def optionalHttpDateHeader(headerName: String): EndpointIO[Option[Instant]] =
    header[Option[String]](headerName).mapDecode[Option[Instant]] {
      case None    => DecodeResult.Value(None)
      case Some(v) => DecodeResult.fromEitherString(v, Header.parseHttpDate(v)).map(Some(_))
    }(_.map(Header.toHttpDateString))

  private val ifModifiedSinceHeader: EndpointIO[Option[Instant]] = optionalHttpDateHeader(HeaderNames.IfModifiedSince)

  private val lastModifiedHeader: EndpointIO[Option[Instant]] = optionalHttpDateHeader(HeaderNames.LastModified)

  private val contentTypeHeader: EndpointIO[Option[MediaType]] = header[Option[String]](HeaderNames.ContentType).mapDecode {
    case None    => DecodeResult.Value(None)
    case Some(v) => DecodeResult.fromEitherString(v, MediaType.parse(v)).map(Some(_))
  }(_.map(_.toString))

  private val etagHeader: EndpointIO[Option[ETag]] = header[Option[String]](HeaderNames.Etag).mapDecode[Option[ETag]] {
    case None    => DecodeResult.Value(None)
    case Some(v) => DecodeResult.fromEitherString(v, ETag.parse(v)).map(Some(_))
  }(_.map(_.toString))

  private val rangeHeader: EndpointIO[Option[Range]] = header[Option[String]](HeaderNames.Range).mapDecode[Option[Range]] {
    case None    => DecodeResult.Value(None)
    case Some(v) => DecodeResult.fromEitherString(v, Range.parse(v).map(_.headOption))
  }(_.map(_.toString))

  private val acceptEncodingHeader: EndpointIO[Option[String]] =
    header[Option[String]](HeaderNames.AcceptEncoding).mapDecode[Option[String]] {
      case None    => DecodeResult.Value(None)
      case Some(v) => DecodeResult.fromEitherString(v, Right(v).map(h => Option(h)))
    }(header => header)

  private val contentEncodingHeader: EndpointIO[Option[String]] =
    header[Option[String]](HeaderNames.ContentEncoding).mapDecode[Option[String]] {
      case None    => DecodeResult.Value(None)
      case Some(v) => DecodeResult.fromEitherString(v, Right(Some(v)))
    }(header => header)

  private def staticGetEndpoint[T](
      prefix: EndpointInput[Unit],
      body: EndpointOutput[T]
  ): Endpoint[StaticInput, StaticErrorOutput, StaticOutput[T], Any] = {
    endpoint.get
      .in(prefix)
      .in(
        pathsWithoutDots
          .and(ifNoneMatchHeader)
          .and(ifModifiedSinceHeader)
          .and(rangeHeader)
          .and(acceptEncodingHeader)
          .map[StaticInput]((t: (List[String], Option[List[ETag]], Option[Instant], Option[Range], Option[String])) =>
            StaticInput(t._1, t._2, t._3, t._4, t._5)
          )(fi => (fi.path, fi.ifNoneMatch, fi.ifModifiedSince, fi.range, fi.acceptEncoding))
      )
      .errorOut(
        oneOf[StaticErrorOutput](
          oneOfMappingClassMatcher(
            StatusCode.NotFound,
            emptyOutputAs(StaticErrorOutput.NotFound),
            StaticErrorOutput.NotFound.getClass
          ),
          oneOfMappingClassMatcher(
            StatusCode.BadRequest,
            emptyOutputAs(StaticErrorOutput.BadRequest),
            StaticErrorOutput.BadRequest.getClass
          ),
          oneOfMappingClassMatcher(
            StatusCode.RangeNotSatisfiable,
            emptyOutputAs(StaticErrorOutput.RangeNotSatisfiable),
            StaticErrorOutput.RangeNotSatisfiable.getClass
          )
        )
      )
      .out(
        oneOf[StaticOutput[T]](
          oneOfMappingClassMatcher(StatusCode.NotModified, emptyOutputAs(StaticOutput.NotModified), StaticOutput.NotModified.getClass),
          oneOfMappingClassMatcher(
            StatusCode.PartialContent,
            body
              .and(lastModifiedHeader)
              .and(header[Option[Long]](HeaderNames.ContentLength))
              .and(contentTypeHeader)
              .and(etagHeader)
              .and(header[Option[String]](HeaderNames.AcceptRanges))
              .and(header[Option[String]](HeaderNames.ContentRange))
              .map[StaticOutput.FoundPartial[T]](
                (t: (T, Option[Instant], Option[Long], Option[MediaType], Option[ETag], Option[String], Option[String])) =>
                  StaticOutput.FoundPartial(t._1, t._2, t._3, t._4, t._5, t._6, t._7)
              )(fo => (fo.body, fo.lastModified, fo.contentLength, fo.contentType, fo.etag, fo.acceptRanges, fo.contentRange)),
            classOf[StaticOutput.FoundPartial[T]]
          ),
          oneOfMappingClassMatcher(
            StatusCode.Ok,
            body
              .and(lastModifiedHeader)
              .and(header[Option[Long]](HeaderNames.ContentLength))
              .and(contentTypeHeader)
              .and(etagHeader)
              .and(contentEncodingHeader)
              .map[StaticOutput.Found[T]]((t: (T, Option[Instant], Option[Long], Option[MediaType], Option[ETag], Option[String])) =>
                StaticOutput.Found(t._1, t._2, t._3, t._4, t._5, t._6)
              )(fo => (fo.body, fo.lastModified, fo.contentLength, fo.contentType, fo.etag, fo.contentEncoding)),
            classOf[StaticOutput.Found[T]]
          )
        )
      )
  }

  private def staticHeadEndpoint(
      prefix: EndpointInput[Unit]
  ): Endpoint[HeadInput, StaticErrorOutput, HeadOutput, Any] = {
    endpoint.head
      .in(prefix)
      .in(pathsWithoutDots.map[HeadInput](t => HeadInput(t))(_.path))
      .errorOut(
        oneOf[StaticErrorOutput](
          oneOfMappingClassMatcher(
            StatusCode.BadRequest,
            emptyOutputAs(StaticErrorOutput.BadRequest),
            StaticErrorOutput.BadRequest.getClass
          ),
          oneOfMappingClassMatcher(
            StatusCode.NotFound,
            emptyOutputAs(StaticErrorOutput.NotFound),
            StaticErrorOutput.NotFound.getClass
          )
        )
      )
      .out(
        oneOf[HeadOutput](
          oneOfMappingClassMatcher(
            StatusCode.Ok,
            header[Option[String]](HeaderNames.AcceptRanges)
              .and(header[Option[Long]](HeaderNames.ContentLength))
              .and(contentTypeHeader)
              .map[HeadOutput.Found]((t: (Option[String], Option[Long], Option[MediaType])) => HeadOutput.Found(t._1, t._2, t._3))(fo =>
                (fo.acceptRanges, fo.contentLength, fo.contentType)
              ),
            classOf[HeadOutput.Found]
          )
        )
      )
  }

  def filesGetEndpoint(prefix: EndpointInput[Unit]): Endpoint[StaticInput, StaticErrorOutput, StaticOutput[FileRange], Any] =
    staticGetEndpoint(prefix, fileRangeBody)

  def resourcesGetEndpoint(prefix: EndpointInput[Unit]): Endpoint[StaticInput, StaticErrorOutput, StaticOutput[InputStream], Any] =
    staticGetEndpoint(prefix, inputStreamBody)

  /** A server endpoint, which exposes files from local storage found at `systemPath`, using the given `prefix`. Typically, the prefix is a
    * path, but it can also contain other inputs. For example:
    *
    * {{{
    * filesServerEndpoint("static" / "files")("/home/app/static")
    * }}}
    *
    * A request to `/static/files/css/styles.css` will try to read the `/home/app/static/css/styles.css` file.
    */
  def filesGetServerEndpoint[F[_]](prefix: EndpointInput[Unit])(
      systemPath: String
  ): ServerEndpoint[StaticInput, StaticErrorOutput, StaticOutput[FileRange], Any, F] =
    ServerEndpoint(filesGetEndpoint(prefix), (m: MonadError[F]) => Files.get(systemPath)(m))

  /** A server endpoint, used to verify if sever supports range requests for file under particular path Additionally it verify file
    * existence and returns its size
    */
  def filesHeadServerEndpoint[F[_]](prefix: EndpointInput[Unit])(
      systemPath: String
  ): ServerEndpoint[HeadInput, StaticErrorOutput, HeadOutput, Any, F] =
    ServerEndpoint(staticHeadEndpoint(prefix), (m: MonadError[F]) => Files.head(systemPath)(m))

  /** Create pair of endpoints (head, get) for particular file */
  def fileServerEndpoints[F[_]](prefix: EndpointInput[Unit])(
      systemPath: String
  ): List[ServerEndpoint[_, StaticErrorOutput, _, Any, F]] =
    List(filesHeadServerEndpoint(prefix)(systemPath), filesGetServerEndpoint(prefix)(systemPath))

  /** A server endpoint, which exposes a single file from local storage found at `systemPath`, using the given `path`.
    *
    * {{{
    * fileServerEndpoint("static" / "hello.html")("/home/app/static/data.html")
    * }}}
    */
  def fileGetServerEndpoint[F[_]](path: EndpointInput[Unit])(
      systemPath: String
  ): ServerEndpoint[StaticInput, StaticErrorOutput, StaticOutput[FileRange], Any, F] =
    ServerEndpoint(removePath(filesGetEndpoint(path)), (m: MonadError[F]) => Files.get(systemPath)(m))

  /** A server endpoint, which exposes resources available from the given `classLoader`, using the given `prefix`. Typically, the prefix is
    * a path, but it can also contain other inputs. For example:
    *
    * {{{
    * resourcesServerEndpoint("static" / "files")(classOf[App].getClassLoader, "app")
    * }}}
    *
    * A request to `/static/files/css/styles.css` will try to read the `/app/css/styles.css` resource.
    */
  def resourcesGetServerEndpoint[F[_]](prefix: EndpointInput[Unit])(
      classLoader: ClassLoader,
      resourcePrefix: String
  ): ServerEndpoint[StaticInput, StaticErrorOutput, StaticOutput[InputStream], Any, F] =
    ServerEndpoint(resourcesGetEndpoint(prefix), (m: MonadError[F]) => Resources(classLoader, resourcePrefix)(m))

  /** A server endpoint, which exposes a single resource available from the given `classLoader` at `resourcePath`, using the given `path`.
    *
    * {{{
    * resourceServerEndpoint("static" / "hello.html")(classOf[App].getClassLoader, "app/data.html")
    * }}}
    */
  def resourceGetServerEndpoint[F[_]](prefix: EndpointInput[Unit])(
      classLoader: ClassLoader,
      resourcePath: String,
      useGzippedIfAvailable: Boolean = false
  ): ServerEndpoint[StaticInput, StaticErrorOutput, StaticOutput[InputStream], Any, F] =
    ServerEndpoint(
      removePath(resourcesGetEndpoint(prefix)),
      (m: MonadError[F]) => Resources(classLoader, resourcePath, useGzippedIfAvailable = useGzippedIfAvailable)(m)
    )

  private def removePath[T](e: Endpoint[StaticInput, StaticErrorOutput, StaticOutput[T], Any]) =
    e.mapIn(i => i.copy(path = Nil))(i => i.copy(path = Nil))
}

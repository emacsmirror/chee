package chee.properties

import java.time.Duration
import better.files._
import com.typesafe.scalalogging.LazyLogging
import scala.util.{Try, Success, Failure}
import chee.Timing
import chee.util.files._
import chee.metadata.{ MetadataExtract, MetadataFile }
import MapGet._

trait Extraction {
  def idents: Set[Ident]
  def extractM(file: File): MapGet[PropertyMap]
  final def extract(file: File): PropertyMap = extractM(file).result(LazyMap())
  def mapIdents(f: Ident => Ident): Extraction
}

object Extraction {
  def assertExists[B](f: File)(body: => B): B =
    if (f.exists && f.isRegularFile) body
    else throw new java.nio.file.NoSuchFileException(s"file not found: ${f.path}")

  def added(dt: DateTime) = Property(Ident.added, dt.instant.toString)

  def all(mf: MetadataFile) = List(
    new BasicExtract(),
    new ImageExtract(),
    new ChecksumExtract(),
    new MetadataExtract(mf))
}

final class BasicExtract(mapping: Ident => Ident = identity) extends Extraction {
  import scala.util.Try
  import PropertyMap._
  import chee.util.files._

  val idents = Set(Ident.path, Ident.filename, Ident.length,
    Ident.lastModified, Ident.mimetype, Ident.extension).map(mapping)

  def mapIdents(f: Ident => Ident) = new BasicExtract(mapping andThen f)

  def extractM(file: File) =
    unit(Try(basicFile(file)).getOrElse(empty))

  def basicFile(f: File): PropertyMap = Extraction.assertExists(f) {
    PropertyMap(
      mapping(Ident.path) -> f.path.toString(),
      mapping(Ident.filename) -> f.name,
      mapping(Ident.length) -> f.size.toString,
      mapping(Ident.lastModified) -> f.lastModifiedTime.toEpochMilli.toString
    ) +? mimeType(f) +? f.getExtension.map(ext => mapping(Ident.extension) -> ext)
  }

  def mimeType(f: File): Option[Property] =
    f.mimeType.map(mt => Property(mapping(Ident.mimetype), mt.getBaseType))
}

final class ImageExtract(mapping: Ident => Ident = identity) extends Extraction with LazyLogging {
  import com.drew.imaging.ImageMetadataReader
  import com.sksamuel.scrimage.{Image, ImageMetadata}

  val idents = (Ident.imageProperties.toSet).map(mapping)

  val exifTags = Map(
    0x0112 -> mapping(Ident.orientation),
    0x010f -> mapping(Ident.make),
    0x0110 -> mapping(Ident.model),
    0x9003 -> mapping(Ident.created),
    0x8827 -> mapping(Ident.iso)
  )

  def mapIdents(f: Ident => Ident) = new ImageExtract(mapping andThen f)

  def isImage(f: File): MapGet[Boolean] = value(mapping(Ident.mimetype)).map {
    case Some(mt) if mt startsWith "image/" => true
    case None => f.mimeType.exists(_.getPrimaryType == "image")
    case _ => false
  }

  def extractM(f: File): MapGet[PropertyMap] = Extraction.assertExists(f) {
    isImage(f).map {
      case true => extractMetadata(f) ++ getWidthAndHeight(f)
      case false => PropertyMap.empty
    }
  }

  def getWidthAndHeight(file: File): PropertyMap = {
    Try(Image.fromPath(file.path)) match {
      case Success(img) =>
        PropertyMap(
          (mapping(Ident.width) -> img.width.toString),
          (mapping(Ident.height) -> img.height.toString))
      case Failure(ex) =>
        val msg = s"Cannot load image: ${file.path}"
        if (logger.underlying.isTraceEnabled) logger.warn(msg, ex)
        else logger.warn(s"$msg: ${ex.getMessage}")
        PropertyMap.empty
    }
  }

  def extractMetadata(f: File): PropertyMap = {
    def logTime(m: Try[PropertyMap], d: Duration): Unit = m match {
      case Success(_) =>
        logger.trace(s"Extracted image properties from ${f.path} in ${Timing.format(d)}")
      case Failure(err) =>
        logger.trace(s"Cannot get image properties from ${f.path}: ${err.getMessage}")
    }

    // using scrimage.ImageMetaData.fromFile() does not close file handles
    val props = Timing.timed(logTime) {
      Try(ImageMetadataReader.readMetadata(f.toJava))
        .map(ImageMetadata.fromMetadata)
        .map(fromMetadata)
    }
    props.toOption.getOrElse(PropertyMap.empty)
  }

  def fromMetadata(meta: ImageMetadata): PropertyMap = {
    val tags = meta.tagsBy(t => exifTags.keySet(t.`type`))
    tags.foldLeft(PropertyMap.empty) { (pm, tag) =>
      val ident = exifTags(tag.`type`)
      if (ident == mapping(Ident.created)) {
        LocalDateTimeConverter.parse(tag.rawValue).right.map(_.asString) match {
          case Right(dt) => pm + (ident -> dt)
          case _ => pm
        }
      } else {
        pm + (ident -> tag.rawValue.trim)
      }
    }
  }
}

final class ChecksumExtract(mapping: Ident => Ident = identity) extends Extraction with LazyLogging {

  val idents = Set(Ident.checksum).map(mapping)

  private def logTime(file: File)(map: MapGet[PropertyMap], d: Duration): Unit =
    logger.trace(s"Created checksum for ${file.path} in ${Timing.format(d)}")

  def extractM(file: File) = Timing.timed(logTime(file)) {
    unit(PropertyMap(checksum(file)))
  }

  def checksum(f: File): Property =
    Property(mapping(Ident.checksum), ChecksumExtract.checksum(f))

  def mapIdents(f: Ident => Ident) = new ChecksumExtract(mapping andThen f)
}

object ChecksumExtract {
  import java.io.{InputStream, BufferedInputStream}
  import java.security.{DigestInputStream, MessageDigest}

   /** Reads the input stream while updating the message digest.
    *
    * After this method the input stream is exhausted, but not closed.
    */
  def digestStream(in: InputStream, md: MessageDigest): MessageDigest = {
    val mdin = new BufferedInputStream(new DigestInputStream(in, md))
    while (mdin.read() != -1) {}
    md
  }

  def digestFile(algo: String)(f: File): String = {
    val md = MessageDigest.getInstance(algo)
    for { in <- f.inputStream } digestStream(in, md)
    javax.xml.bind.DatatypeConverter.printHexBinary(md.digest()).toLowerCase()
  }

  def checksum(f: File): String = digestFile("SHA-256")(f)
}

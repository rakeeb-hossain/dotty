package dotty.tools.scaladoc
package site

import java.io.File
import java.nio.file.Files
import java.nio.file.FileVisitOption
import java.nio.file.Path
import java.nio.file.Paths

import scala.util.Try
import collection.JavaConverters._

class StaticSiteContext(
  val root: File,
  val args: Scaladoc.Args,
  val sourceLinks: SourceLinks,
  val snippetCompilerArgs: snippets.SnippetCompilerArgs,
  val snippetChecker: snippets.SnippetChecker)(using val outerCtx: CompilerContext):

  var memberLinkResolver: String => Option[DRI] = _ => None

  private def indexFiles =
    val files = List(new File(root, "index.html"), new File(root, "index.md")).filter { _.exists() }

    if files.size > 1 then
      val msg = s"ERROR: Multiple root index pages found: ${files.map(_.getAbsolutePath)}"
      report.error(msg)

    files.headOption

  def hasIndexFile = indexFiles.nonEmpty

  def emptyIndexTemplate =
    val fakeFile = new File(root, "index.html")
    LoadedTemplate(emptyTemplate(fakeFile, "index"), List.empty, fakeFile)

  def indexTemplate(): Option[LoadedTemplate] =
    indexFiles.flatMap(loadTemplate(_, isBlog = false))

  lazy val layouts: Map[String, TemplateFile] =
    val layoutRoot = new File(root, "_layouts")
    val dirs: Array[File] = Option(layoutRoot.listFiles()).getOrElse(Array())
    dirs.map { it => loadTemplateFile(it)(using this) }.map { it => it.name -> it }.toMap

  lazy val sideBarConfig =
    val sidebarFile = root.toPath.resolve("sidebar.yml")
    if (!Files.exists(sidebarFile)) None
    else Some(Sidebar.load(Files.readAllLines(sidebarFile).asScala.mkString("\n")))

  lazy val templates: Seq[LoadedTemplate] =
    sideBarConfig.fold(loadAllFiles().sortBy(_.templateFile.title.name))(_.map(loadSidebarContent))

  lazy val orphanedTemplates: Seq[LoadedTemplate] = {
    def doFlatten(t: LoadedTemplate): Seq[Path] =
      t.file.toPath +: t.children.flatMap(doFlatten)
    val mainFiles = templates.flatMap(doFlatten)

    val allPaths =
      if !Files.exists(docsPath) then Nil
      else Files.walk(docsPath, FileVisitOption.FOLLOW_LINKS).iterator().asScala.toList

    val orphanedFiles = allPaths.filterNot { p =>
       def name = p.getFileName.toString
       def isMain = name == "index.html" || name == "index.md"
       mainFiles.contains(p) || (isMain && mainFiles.contains(p.getParent))
    }.filter { p =>
        val name = p.getFileName.toString
        name.endsWith(".md") || name.endsWith(".html")
    }

    orphanedFiles.flatMap(p => loadTemplate(p.toFile, isBlog = false))
  }

  lazy val redirectTemplates: Seq[(LoadedTemplate, DRI, DRI)] = {
    def doFlatten(t: LoadedTemplate): Seq[LoadedTemplate] =
      t +: t.children.flatMap(doFlatten)
    val mainFiles = templates.flatMap(doFlatten)
    mainFiles.flatMap { loadedTemplate =>
      val redirectFrom = loadedTemplate.templateFile.settings.getOrElse("page", Map.empty).asInstanceOf[Map[String, Object]].get("redirectFrom")
      def redirectToTemplate(redirectFrom: String) =
        val fakeFile = new File(docsPath.toFile, redirectFrom)
        val driFrom = driFor(fakeFile.toPath)
        val driTo = driFor(loadedTemplate.file.toPath)
        (LoadedTemplate(layouts("redirect"), List.empty, fakeFile), driFrom, driTo)
      redirectFrom.map {
        case redirectFrom: String => Seq(redirectToTemplate(redirectFrom))
        case redirects: List[?] => redirects.asInstanceOf[List[String]].map(redirectToTemplate)
      }.getOrElse(Nil)
    }
  }

  val docsPath = root.toPath.resolve("docs")

  private def isValidTemplate(file: File): Boolean =
    (file.isDirectory && !file.getName.startsWith("_")) ||
      file.getName.endsWith(".md") ||
      file.getName.endsWith(".html")


  private def loadTemplate(from: File, isBlog: Boolean): Option[LoadedTemplate] =
    if (!isValidTemplate(from)) None else
      try
        val topLevelFiles = if isBlog then Seq(from, new File(from, "_posts")) else Seq(from)
        val allFiles = topLevelFiles.filter(_.isDirectory).flatMap(_.listFiles())
        val (indexes, children) = allFiles.flatMap(loadTemplate(_, isBlog)).partition(_.templateFile.isIndexPage())

        def loadIndexPage(): TemplateFile =
          indexes match
            case Nil =>
              args.defaultTemplate.fold(emptyTemplate(from, from.getName))(layouts(_).copy(title = TemplateName.FilenameDefined(from.getName))).copy(file = File(from, "index.html"))
            case Seq(loadedTemplate) => loadedTemplate.templateFile
            case _ =>
              // TODO (https://github.com/lampepfl/scaladoc/issues/238): provide proper error handling
              val msg = s"ERROR: Multiple index pages for $from found in ${indexes.map(_.file)}"
              throw new java.lang.RuntimeException(msg)

        val templateFile = if (from.isDirectory) loadIndexPage() else loadTemplateFile(from)(using this)

        def dateFrom(p: LoadedTemplate, default: String = "1900-01-01"): String =
          val pageSettings = p.templateFile.settings.get("page").collect{ case m: Map[String @unchecked, _] => m }
          pageSettings.flatMap(_.get("date").collect{ case s: String => s}).getOrElse(default) // blogs without date are last

        val processedChildren: Seq[LoadedTemplate] = if !isBlog then children.sortBy(_.templateFile.title.name) else
          children.sortBy(dateFrom(_)).reverse

        processedChildren.foreach { child =>
          val regex = raw"(\d*-\d*-\d*)-(.*)".r
          val setDate = dateFrom(child, "<no date>")
          child.templateFile.name match
            case regex(date, name) if date != setDate =>
              val msg = s"Date $date in blog file: ${child.templateFile.name} doesn't match date from settings: $setDate."
              report.warn(msg, from)
            case name =>
        }

        val processedTemplate = // Set provided name as arg in page for `docs`
          if templateFile.file.getParentFile.toPath == docsPath && templateFile.isIndexPage() then
            if templateFile.title.name != "index" then
              report.warn("Property `title` will be overridden by project name", from)

            templateFile.copy(title = TemplateName.FilenameDefined(args.name))
          else templateFile

        Some(LoadedTemplate(processedTemplate, processedChildren.toList, processedTemplate.file))
      catch
          case e: RuntimeException =>
            // TODO (https://github.com/lampepfl/scaladoc/issues/238): provide proper error handling
            e.printStackTrace()
            None

  private def loadSidebarContent(entry: Sidebar): LoadedTemplate = entry match
    case Sidebar.Page(optionTitle, pagePath) =>
      val isBlog = optionTitle == Some("Blog")
      val path = if isBlog then "blog" else
        if Files.exists(root.toPath.resolve(pagePath)) then pagePath
        else pagePath.stripSuffix(".html") + ".md"

      // val file = root.toPath.resolve(path).toFile
      val LoadedTemplate(template, children, file) = loadTemplate(root.toPath.resolve(path).toFile, isBlog).get // Add proper logging if file does not exisits
      optionTitle match
        case Some(title) =>
          val newTitle = template.title match
            case t: TemplateName.YamlDefined => t
            case _: TemplateName.FilenameDefined => TemplateName.SidebarDefined(title)
            case t: TemplateName.SidebarDefined => t // should never reach this path
          LoadedTemplate(template.copy(settings = template.settings + ("title" -> newTitle.name), title = newTitle), children, file)
        case None =>
          LoadedTemplate(template.copy(settings = template.settings), children, file)

    case Sidebar.Category(optionTitle, optionIndexPath, nested) =>
      optionIndexPath match
        case Some(indexPath) => // There is an index page for section, let's load it
          loadSidebarContent(Sidebar.Page(optionTitle, indexPath)).copy(children = nested.map(loadSidebarContent))
        case None => // No index page, let's create default fake file.
          val title = optionTitle match
            case Some(t) => t
            case None => "index"
          val sidebarContent = nested.map(loadSidebarContent)
          // Heuristic to add section index.html around its child pages. Otherwise we put it in directory named after title of section in top-level
          def longestPrefix(s1: String, s2: String): String = s1.zip(s2).takeWhile(Function.tupled(_ == _)).map(_._1).mkString
          val sortedNames = sidebarContent.collect {
            case t if t.file.getName.reverse.dropWhile(_ != '.').reverse != "index" =>
              t.file.toPath.toString
          }.sorted
          def indexPathTemplate(s: String) = Paths.get(root.toString, s, "index.html").toFile
          val fakeFile = Option.when(sortedNames.nonEmpty)(sortedNames).map { s =>
            indexPathTemplate(longestPrefix(s.head, s.last))
          }.filter(_.exists).getOrElse {
            indexPathTemplate(s"docs/${title.toLowerCase}")
          }
          LoadedTemplate(
            args.defaultTemplate.fold(emptyTemplate(fakeFile, title))(layouts(_).copy(title = TemplateName.FilenameDefined(title))),
            sidebarContent,
            fakeFile
          )

  private def loadAllFiles() =
    def dir(name: String)= List(new File(root, name)).filter(_.isDirectory)
    dir("docs").flatMap(_.listFiles()).flatMap(loadTemplate(_, isBlog = false))
      ++ dir("blog").flatMap(loadTemplate(_, isBlog = true))

  def driForLink(loadedTemplateFile: File, link: String): Seq[DRI] =
    val pathsDri: Option[Seq[DRI]] = Try {
      val baseFile =
        if
          link.startsWith("/") then root.toPath.resolve(link.drop(1))
        else
          val path = loadedTemplateFile.toPath
          (if Files.isDirectory(path) then path else path.getParent).resolve(link).normalize

      val fileName = baseFile.getFileName.toString
      val baseFileName = if fileName.endsWith(".md")
          then fileName.stripSuffix(".md")
          else fileName.stripSuffix(".html")
      (Seq(
        Some(baseFile.resolveSibling(baseFileName + ".html")),
        Some(baseFile.resolveSibling(baseFileName + ".md"))
      ).flatten.filter(Files.exists(_)) ++ Seq(
        Option.when(baseFileName == "index")(baseFile.getParent)
      ).flatten).map(driFor)
    }.toOption.filter(_.nonEmpty)
    pathsDri.getOrElse(memberLinkResolver(link).toList)

  def driFor(dest: Path): DRI =
    val rawFilePath = root.toPath.relativize(dest)
    val pageName = dest.getFileName.toString
    val dotIndex = pageName.lastIndexOf('.')

    val relativePath =
      if rawFilePath.startsWith(Paths.get("blog","_posts")) then
        val regex = raw"(\d*)-(\d*)-(\d*)-(.*)\..*".r
        pageName.toString match
          case regex(year, month, day, name) =>
            rawFilePath.getParent.resolveSibling(Paths.get(year, month, day, name))
          case _ =>
            val msg = s"Relative path for blog: $rawFilePath doesn't match `yyy-mm-dd-name.md` format."
            report.warn(msg, dest.toFile)
            rawFilePath.resolveSibling(pageName.substring(0, dotIndex))
      else
        if (dotIndex < 0) rawFilePath.resolve("index")
        else rawFilePath.resolveSibling(pageName.substring(0, dotIndex))

    DRI.forPath(relativePath)

  def relativePath(myTemplate: LoadedTemplate) = root.toPath.relativize(myTemplate.file.toPath)

  val projectWideProperties =
    Seq("projectName" -> args.name) ++
      args.projectVersion.map("projectVersion" -> _)

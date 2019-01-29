package org.spongepowered.play.discourse

import java.nio.file.Path

import akka.stream.scaladsl.{FileIO, Source}
import org.spongepowered.play.discourse.model.{DiscoursePost, DiscourseUser}
import play.api.http.Status
import play.api.libs.json.JsObject
import play.api.libs.ws.{WSClient, WSRequest, WSResponse}
import play.api.mvc.MultipartFormData.{DataPart, FilePart}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, TimeoutException}
import scala.util.{Failure, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * An implementation of the RESTful API for the forum software: "Discourse".
  */
trait DiscourseApi extends DiscourseReads {

  /** The base URL of the Discourse instance. */
  val url: String
  /** The API key */
  val key: String
  /** An admin's username */
  val admin: String
  /** Default timeout */
  val timeout: Duration

  val Logger = play.api.Logger("Discourse")

  var isDebugMode: Boolean = false

  protected val ws: WSClient

  // Users

  /**
    * Returns the user URL for the specified username.
    *
    * @param username Username to create URL for
    * @return         User URL
    */
  def userUrl(username: String): String = s"${this.url}/users/$username.json"

  /**
    * Returns true if a Discourse user exists with the given username.
    *
    * @param username Username to find
    * @return True if user exists
    */
  def userExists(username: String): Future[Boolean] = fetchUser(username).map(_.isDefined)

  /**
    * Attempts to retrieve the user with the specified username from the
    * forums.
    *
    * @param username Username to find
    * @return         User data or none
    */
  def fetchUser(username: String): Future[Option[DiscourseUser]]
  = this.request(userUrl(username)).get.map(validateRight(_).map(json => (json \ "user").as[DiscourseUser]))

  /**
    * Creates a new user on the Discourse instance.
    *
    * @param name     User's full name
    * @param username User's username
    * @param email    User's email
    * @param password User's password
    * @param active   True if user is active
    * @return         Either a list of errors or a new User ID
    */
  def createUser(name: String, username: String, email: String, password: String,
                 active: Boolean = true): Future[Either[List[String], Int]] = {
    val data = Map(
      "name" -> Seq(name),
      "username" -> Seq(username),
      "email" -> Seq(email),
      "password" -> Seq(password),
      "active" -> Seq(active.toString))
    this.request(keyedUrl("/users")).post(data).map(validate(_).right.map(json => (json \ "user_id").as[Int]))
  }

  /**
    * Adds a group to the specified user.
    *
    * @param userId   User ID
    * @param groupId  ID of group to add
    */
  def addUserGroup(userId: Int, groupId: Int): Future[List[String]] = {
    val url = keyedUrl(s"/admin/users/$userId/groups")
    val data = Map("group_id" -> Seq(groupId.toString))
    this.request(url).post(data).map(validateLeft)
  }

  /**
    * Sets the avatar for the specified user.
    *
    * @param username   User username
    * @param avatarUrl  Avatar URL
    * @return           List of errors if any
    */
  def setAvatar(username: String, avatarUrl: String): Future[List[String]] = {
    val url = this.url + "/uploads"
    val data = ApiParams(username) + (
      "username" -> Seq(username),
      "url" -> Seq(avatarUrl),
      "type" -> Seq("avatar"),
      "synchronous" -> Seq("true"))
    handleAvatarResponse(this.request(url).post(data), username)
  }

  /**
    * Sets the avatar for the specified user.
    *
    * @param username User username
    * @param path     Avatar file
    * @return         List of errors if any
    */
  def setAvatar(username: String, fileName: String, path: Path): Future[List[String]] = {
    val url = this.url + "/uploads"
    val data = Source(
      DataPart("api_key", this.key)
        :: DataPart("api_username", username)
        :: DataPart("username", username)
        :: FilePart("file", fileName, Some("image/jpeg"), FileIO.fromPath(path))
        :: DataPart("type", "avatar")
        :: DataPart("synchronous", "true")
        :: List())
    handleAvatarResponse(this.request(url).post(data), username)
  }

  private def handleAvatarResponse(future: Future[WSResponse], username: String): Future[List[String]] = {
    future.map(validate).andThen {
      case Success(result) => result.right.foreach { json =>
        val uploadId = (json \ "id").as[Int]
        val url = s"${this.url}/users/$username/preferences/avatar/pick"
        val data = ApiParams(username) + ("upload_id" -> Seq(uploadId.toString))
        this.ws.url(url).put(data).map(validateLeft)
      }
    }.map(_.left.toOption.getOrElse(List.empty))
  }

  // Posts

  /**
    * Creates a new topic as the specified poster.
    *
    * @param poster       Poster username
    * @param title        Topic title
    * @param content      Topic raw content
    * @param categoryId   Optional category id
    * @return             New topic or list of errors
    */
  def createTopic(poster: String, title: String, content: String,
                  categoryId: Option[Int]): Future[Either[List[String], DiscoursePost]] = {
    var params = ApiParams(poster) + (
      "title" -> Seq(title),
      "raw" -> Seq(content))

    categoryId.foreach { categoryId =>
      params += "category" -> Seq(categoryId.toString)
    }

    // Note: Oddly enough, occasionally, discourse will choose to return a
    // "deleted" post if the content is the same when created in rapid
    // succession. Even more, the returned post is missing the "deleted_at"
    // timestamp so it is currently impossible to discern when this happens as
    // far as I can tell.

    if (isDebugMode)
      Logger.info("Creating topic with parameters: " + params)

    this.request(this.url + "/posts").post(params).map(validate(_).right.map(_.as[DiscoursePost]))
  }

  /**
    * Creates a new post as the specified user.
    *
    * @param username User to post as
    * @param topicId  Topic ID
    * @param content  Raw content
    * @return         New post or list of errors
    */
  def createPost(username: String, topicId: Int, content: String): Future[Either[List[String], DiscoursePost]] = {
    val params = ApiParams(username) + (
      "topic_id" -> Seq(topicId.toString),
      "raw" -> Seq(content))

    if (isDebugMode)
      Logger.info("Creating post with parameters: " + params)

    this.request(this.url + "/posts").post(params).map(validate(_).right.map(_.as[DiscoursePost]))
  }

  /**
    * Updates a topic as the specified user.
    *
    * @param username   Username to update as
    * @param topicId    Topic ID
    * @param title      Optional new topic title
    * @param categoryId Optional new category ID
    * @return           List of errors
    */
  def updateTopic(username: String, topicId: Int, title: Option[String], categoryId: Option[Int]): Future[List[String]] = {
    if (title.isEmpty && categoryId.isEmpty)
      return Future(List.empty)

    var params = ApiParams(username) + ("topic_id" -> Seq(topicId.toString))

    title.foreach { title =>
      params += "title" -> Seq(title)
    }

    categoryId.foreach { categoryId =>
      params += "category_id" -> Seq(categoryId.toString)
    }

    this.request(s"${this.url}/t/-/$topicId").put(params).map(validateLeft)
  }

  /**
    * Updates a post as the specified user.
    *
    * @param username User to update as
    * @param postId   Post ID
    * @param content  Raw content
    * @return         List of errors
    */
  def updatePost(username: String, postId: Int, content: String): Future[List[String]] = {
    val params = ApiParams(username) + ("post[raw]" -> Seq(content))
    this.request(s"${this.url}/posts/$postId").put(params).map(validateLeft)
  }

  /**
    * Deletes the specified topic.
    *
    * @param username User to delete as
    * @param topicId  Topic ID
    * @return         List of errors
    */
  def deleteTopic(username: String, topicId: Int): Future[Boolean] = {
    this.request(keyedUrl(s"/t/$topicId", username)).delete().map(s => {
      if (this.isDebugMode)
        Logger.info(s"Topic deletion: $s")
      s.status == Status.OK
    }).recover {
      case e: Exception =>
        if (this.isDebugMode)
          e.printStackTrace()
        false
    }
  }

  // Utils


  /**
    * Awaits the completion of the specified Future based on the configured
    * timeout.
    *
    * @param future Future to wait for
    * @tparam A     Result type
    * @return       Result
    */
  def await[A](future: Future[A]): A = Await.result(future, this.timeout)

  /**
    * Returns true if the Discourse instance is available.
    *
    * @return True if available
    */
  def isAvailable: Boolean = {
    val result = Try(await(this.ws.url(this.url).get().map(_.status == Status.OK).recover {
      case e: Exception => false
    }))
    result match {
      case Success(r) =>
        r
      case Failure(e) =>
        if (e.isInstanceOf[TimeoutException])
          return false
        throw e;
    }
  }

  /**
    * Validates an incoming Discourse API response.
    *
    * @param response Response to validate
    * @return         Return type
    */
  def validate(response: WSResponse): Either[List[String], JsObject] = {
    if (this.isDebugMode) {
      Logger.info("Discourse response:\n" +
        s"Status: ${response.status}\n" +
        s"Body: ${response.body}")
    }

    if (response.status < 200 || response.status >= 300) {
      Left(List(s"Response code ${response.status}: ${response.body}"))
    } else {
      var json: JsObject = null
      try {
        json = response.json.as[JsObject]
      } catch {
        case e: Exception =>
          throw new RuntimeException(s"failed to parse body [${response.body}] as json", e)
      }

      if (json.keys.contains("success") && !(json \ "success").as[Boolean])
        Left((json \ "message").as[String] :: List())
      else
        Right(json)
    }
  }

  /**
    * Validates and returns a list of errors.
    *
    * @param response Response to validate
    * @return         List of errors
    */
  def validateLeft(response: WSResponse): List[String] = validate(response).left.getOrElse(List.empty)

  /**
    * Validates and returns an optional JsObject.
    *
    * @param response Response to validate
    * @return         Json result
    */
  def validateRight(response: WSResponse): Option[JsObject] = validate(response).right.toOption

  /**
    * Constructs a new request to the Discourse instance ready to be sent.
    *
    * @param url  URL of request
    * @return     WSRequest
    */
  def request(url: String): WSRequest = {
    if (this.isDebugMode)
      Logger.info(s"Request: $url")
    this.ws.url(url)
  }

  /**
    * Returns a URL for the Discourse instance with API parameters appended to
    * the query string.
    *
    * @param url      Base URL
    * @param username Username to authenticate as
    * @return         URL with API parameters
    */
  def keyedUrl(url: String, username: String = this.admin)
  = s"${this.url}$url?api_key=${this.key}&api_username=$username"

  /**
    * Returns a Map with Discourse API parameters.
    *
    * @param username Username to authenticate as
    * @return         Map with API parameters
    */
  def ApiParams(username: String = this.admin) = Map(
    "api_key" -> Seq(this.key),
    "api_username" -> Seq(username))

}

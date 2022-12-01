package lila.forum

import Filter._
import org.joda.time.DateTime
import reactivemongo.akkastream.{ cursorProducer, AkkaStreamCursor }
import reactivemongo.api.ReadPreference

import lila.db.dsl._
import lila.user.User

final class PostRepo(val coll: Coll, filter: Filter = Safe)(implicit
    ec: scala.concurrent.ExecutionContext
) {

  def forUser(user: Option[User]) =
    withFilter(user.filter(_.marks.troll).fold[Filter](Safe) { u =>
      SafeAnd(u.id)
    })
  def withFilter(f: Filter) = if (f == filter) this else new PostRepo(coll, f)
  def unsafe                = withFilter(Unsafe)

  import BSONHandlers.PostBSONHandler

  private val noTroll = $doc("troll" -> false)
  private val trollFilter = filter match {
    case Safe       => noTroll
    case SafeAnd(u) => $or(noTroll, $doc("userId" -> u))
    case Unsafe     => $empty
  }

  def byIds(ids: Seq[Post.ID]) = coll.byIds[Post](ids)

  def byCategAndId(categSlug: String, id: String): Fu[Option[Post]] =
    coll.one[Post](selectCateg(categSlug) ++ $id(id))

  def countBeforeNumber(topicId: String, number: Int): Fu[Int] =
    coll.countSel(selectTopic(topicId) ++ $doc("number" -> $lt(number)))

  def isFirstPost(topicId: String, postId: String): Fu[Boolean] =
    coll.primitiveOne[String](selectTopic(topicId), $sort.createdAsc, "_id") dmap { _ contains postId }

  def countByTopic(topic: Topic): Fu[Int] =
    coll.countSel(selectTopic(topic.id))

  def lastByCateg(categ: Categ): Fu[Option[Post]] =
    coll.find(selectCateg(categ.id)).sort($sort.createdDesc).one[Post]

  def lastByTopic(topic: Topic): Fu[Option[Post]] =
    coll.find(selectTopic(topic.id)).sort($sort.createdDesc).one[Post]

  def recentInCategs(nb: Int)(categIds: List[String], langs: List[String]): Fu[List[Post]] =
    coll
      .find(selectCategs(categIds) ++ selectLangs(langs) ++ selectNotHidden ++ selectNotErased)
      .sort($sort.createdDesc)
      .cursor[Post]()
      .list(nb)

  def recentInCateg(categId: String, nb: Int): Fu[List[Post]] =
    coll
      .find(selectCateg(categId) ++ selectNotHidden ++ selectNotErased)
      .sort($sort.createdDesc)
      .cursor[Post]()
      .list(nb)

  def allByUserCursor(user: User): AkkaStreamCursor[Post] =
    coll
      .find($doc("userId" -> user.id))
      .cursor[Post](ReadPreference.secondaryPreferred)

  def countByCateg(categ: Categ): Fu[Int] =
    coll.countSel(selectCateg(categ.id))

  def remove(post: Post): Funit =
    coll.delete.one($id(post.id)).void

  def removeByTopic(topicId: String): Funit =
    coll.delete.one(selectTopic(topicId)).void

  def selectTopic(topicId: String) = $doc("topicId" -> topicId) ++ trollFilter

  def selectCateg(categId: String)         = $doc("categId" -> categId) ++ trollFilter
  def selectCategs(categIds: List[String]) = $doc("categId" $in categIds) ++ trollFilter

  val selectNotHidden = $doc("hidden" -> false)
  val selectNotErased = $doc("erasedAt" $exists false)

  def selectLangs(langs: List[String]) =
    if (langs.isEmpty) $empty
    else $doc("lang" $in langs)

  def findDuplicate(post: Post): Fu[Option[Post]] =
    coll.one[Post](
      $doc(
        "createdAt" $gt DateTime.now.minusHours(1),
        "userId" -> ~post.userId,
        "text"   -> post.text
      )
    )

  def sortQuery = $sort.createdAsc

  def idsByTopicId(topicId: String): Fu[List[String]] =
    coll.distinctEasy[String, List]("_id", $doc("topicId" -> topicId), ReadPreference.secondaryPreferred)

  def allUserIdsByTopicId(topicId: String): Fu[List[User.ID]] =
    coll.distinctEasy[User.ID, List](
      "userId",
      $doc("topicId" -> topicId) ++ selectNotErased,
      ReadPreference.secondaryPreferred
    )

  def nonGhostCursor =
    coll
      .find($doc("userId" $ne User.ghostId))
      .cursor[Post](ReadPreference.secondaryPreferred)
}
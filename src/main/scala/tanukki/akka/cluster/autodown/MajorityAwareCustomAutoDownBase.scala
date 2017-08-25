package tanukki.akka.cluster.autodown

import akka.actor.Address
import akka.cluster.ClusterEvent._
import akka.cluster.{MemberStatus, Member}
import scala.collection.immutable
import scala.collection.immutable.SortedSet
import scala.concurrent.duration.FiniteDuration

abstract class MajorityAwareCustomAutoDownBase(autoDownUnreachableAfter: FiniteDuration)
    extends CustomAutoDownBase(autoDownUnreachableAfter) with SplitBrainResolver {

  private var leader = false
  private var roleLeader: Map[String, Boolean] = Map.empty

  private var membersByAddress: immutable.SortedSet[Member] = immutable.SortedSet.empty(Member.ordering)

  def receiveEvent = {
     case LeaderChanged(leaderOption) =>
      leader = leaderOption.exists(_ == selfAddress)
      onLeaderChanged(leaderOption)
    case RoleLeaderChanged(role, leaderOption) =>
      roleLeader = roleLeader + (role -> leaderOption.exists(_ == selfAddress))
      onRoleLeaderChanged(role, leaderOption)
    case MemberUp(m) =>
      println("MemberUp: " + m.toString)
      replaceMember(m)
    case UnreachableMember(m) =>
      println("UnreachableMember: " + m.toString)
      replaceMember(m)
      unreachableMember(m)

    case ReachableMember(m)   =>
      println("ReachableMember: " + m.toString)
      replaceMember(m)
      remove(m)
    case MemberLeft(m) =>
      println("MemberLeft: " + m.toString)
      replaceMember(m)
    case MemberExited(m) =>
      println("MemberExited: " + m.toString)
      replaceMember(m)
    case MemberRemoved(m, prev)  =>
      println("MemberRemoved: " + m.toString)
      remove(m)
      removeMember(m)
      onMemberRemoved(m, prev)
  }

  def isLeader: Boolean = leader

  def isRoleLeaderOf(role: String): Boolean = roleLeader.getOrElse(role, false)

  def onLeaderChanged(leader: Option[Address]): Unit = {}

  def onRoleLeaderChanged(role: String, leader: Option[Address]): Unit = {}

  def onMemberRemoved(member: Member, previousStatus: MemberStatus): Unit = {}

  override def initialize(state: CurrentClusterState): Unit = {
    leader = state.leader.exists(_ == selfAddress)
    roleLeader = state.roleLeaderMap.mapValues(_.exists(_ == selfAddress))
    membersByAddress = immutable.SortedSet.empty(Member.ordering) union state.members.filterNot {m =>
      m.status == MemberStatus.Removed
    }
    super.initialize(state)
  }

  def replaceMember(member: Member): Unit = {
    membersByAddress -= member
    membersByAddress += member
  }

  def removeMember(member: Member): Unit = {
    membersByAddress -= member
  }

  def isLeaderOf(majorityRole: Option[String]): Boolean = majorityRole.fold(isLeader)(isRoleLeaderOf)

  def targetMember: SortedSet[Member] = membersByAddress.filter { m =>
    (m.status == MemberStatus.Up || m.status == MemberStatus.Leaving) &&
      !pendingUnreachableMembers.contains(m)
  }

  def majorityMemberOf(role: Option[String]): SortedSet[Member] = {
    val ms = targetMember
    role.fold(ms)(r => ms.filter(_.hasRole(r)))
  }

  def isMajority(role: Option[String]): Boolean = {
    val ms = majorityMemberOf(role)
    println(ms.toString)
    val okMembers = ms filter isOK
    println(okMembers.toString)
    val koMembers = ms -- okMembers
    println(okMembers.toString)

    val isEqual = okMembers.size == koMembers.size
    return (okMembers.size > koMembers.size ||
      isEqual && ms.headOption.map(okMembers.contains(_)).getOrElse(true))
  }

  def isMajorityAfterDown(members: Set[Member], role: Option[String]): Boolean = {
    val minus = if (role.isEmpty) members else {
      val r = role.get
      members.filter(_.hasRole(r))
    }
    val ms = majorityMemberOf(role) -- minus
    val okMembers = ms filter isOK
    val koMembers = ms -- okMembers

    val isEqual = okMembers.size == koMembers.size
    return (okMembers.size > koMembers.size ||
      isEqual && ms.headOption.map(okMembers.contains(_)).getOrElse(true))
  }

  private def isOK(member: Member) = {
    (member.status == MemberStatus.Up || member.status == MemberStatus.Leaving) &&
    (!pendingUnreachableMembers.contains(member) && !unstableUnreachableMembers.contains(member))
  }

  private def isKO(member: Member): Boolean = !isOK(member)
}

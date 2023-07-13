abstract sig People {}

one sig Legolas, Ian, Mane extends People {}

sig Group {
	participants : set People
}
{
	some participants
}

fun orderBy : Int {
    #participants
}

run {} for 3 but 5 int

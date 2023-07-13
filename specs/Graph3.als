sig Node {
    adj : set Node
}

fun orderBy : Int {
    sub[#adj, #^adj]
    //sub[#Node, #adj]
}

run Connected {
    all n: Node | Node in n.*adj
} for 3 Node, 6 int // orderBy 10*#Node - #adj


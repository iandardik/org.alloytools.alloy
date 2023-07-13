sig Node {
    adj : set Node
}

fun orderBy : Int {
    sub[0, #adj]
}

run Connected {
    all n: Node | Node in n.*adj
} for exactly 3 Node, 6 int


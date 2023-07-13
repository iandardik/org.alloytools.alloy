sig Node {
    adj : set Node
}

fun orderBy : Int {
    sub[0, #Node]
}

fun groupBy : Int {
    #adj
}

run Connected {
    all n: Node | Node in n.*adj
} for 3 Node, 6 int //, orderBy sub[0, #adj]


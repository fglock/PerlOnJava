use strict;
use warnings;
use threads;

print "1..2\n";

sub capture_signature {
    my $pattern = qr/^(a+)(b+)(c+)$/;
    return 'aaabbbccc' =~ $pattern ? join(',', $1, $2, $3) : 'no match';
}

print capture_signature() eq 'aaa,bbb,ccc'
    ? "ok 1 - parent capture variables remain dynamic\n"
    : "not ok 1 - parent capture variables remain dynamic\n";
print threads->create(\&capture_signature)->join() eq 'aaa,bbb,ccc'
    ? "ok 2 - child capture variables retain magic after snapshot\n"
    : "not ok 2 - child capture variables retain magic after snapshot\n";

use strict;
use warnings;

print "1..8\n";

my ($base, $stack, $self);

sub stack_from {
    my ($level) = @_;
    my $result = '';
    while (my @caller = caller($level++)) {
        $result .= "($caller[3]:" . ($caller[2] - $base) . ')';
    }
    return $result;
}

$base = __LINE__;
"" =~ /(?{ $stack = stack_from(1) })/;
print $stack eq ''
    ? "ok 1 - inline callback is a pseudo-block\n"
    : "not ok 1 - inline callback is a pseudo-block ($stack)\n";

$base = __LINE__;
my $quoted = qr/(?{ $stack = stack_from(1) })/;
"" =~ /$quoted/;
print $stack eq '(main::__ANON__:2)'
    ? "ok 2 - quoted callback contributes anonymous frame\n"
    : "not ok 2 - quoted callback contributes anonymous frame ($stack)\n";

sub invoke_quoted { "" =~ /$quoted/ }
$base = __LINE__;
invoke_quoted();
print $stack eq '(main::__ANON__:-1)(main::invoke_quoted:1)'
    ? "ok 3 - quoted callback preserves recursive caller chain\n"
    : "not ok 3 - quoted callback preserves recursive caller chain ($stack)\n";

sub record_stack { $stack = stack_from(1) }
$base = __LINE__;
my $nested = qr/(?{ record_stack() })/;
"" =~ /$nested/;
print $stack =~ /^\(main::record_stack:\d+\)\(main::__ANON__:\d+\)$/
    ? "ok 4 - nested callback call keeps anonymous owner\n"
    : "not ok 4 - nested callback call keeps anonymous owner ($stack)\n";

sub direct_self { "" =~ /(?{ $self = CORE::__SUB__ })/ }
direct_self();
print $self == \&direct_self
    ? "ok 5 - inline callback inherits enclosing __SUB__\n"
    : "not ok 5 - inline callback inherits enclosing __SUB__\n";

my $self_quoted = qr/(?{ $self = CORE::__SUB__ })/;
sub first_owner { "" =~ /$self_quoted/ }
sub second_owner { "AB" =~ /A${self_quoted}B/ }
first_owner();
print $self == \&first_owner
    ? "ok 6 - quoted callback adopts first match owner\n"
    : "not ok 6 - quoted callback adopts first match owner\n";
second_owner();
print $self == \&second_owner
    ? "ok 7 - interpolated quoted callback adopts current match owner\n"
    : "not ok 7 - interpolated quoted callback adopts current match owner\n";
first_owner();
print $self == \&first_owner
    ? "ok 8 - reused quoted callback does not retain stale owner\n"
    : "not ok 8 - reused quoted callback does not retain stale owner\n";

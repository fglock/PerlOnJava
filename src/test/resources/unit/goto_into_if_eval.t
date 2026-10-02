use strict;
use warnings;
use utf8;
my $error = '';
my @warnings;
local $SIG{__WARN__} = sub { push @warnings, $_[0] };
eval {
    goto ここ;
    if (undef) {
        ここ: {
            my $value = 'unreachable';
        }
    }
};
$error = $@;
my $ok = $error =~ /Use of "goto" to jump into a construct is no longer permitted/
    || grep { /Use of "goto" to jump into a construct is deprecated/ } @warnings;
print "1..1\n";
print(($ok ? 'ok' : 'not ok'),
    " 1 - eval goto cannot enter an if body\n");

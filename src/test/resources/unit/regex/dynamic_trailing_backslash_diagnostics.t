use strict;
use warnings;
use utf8;
use Test::More;

for my $pattern ('\\', "ネ\\") {
    eval { qr/$pattern/ };
    my $error = $@;
    $error =~ s/ at \Q${\__FILE__}\E line \d+\.\n\z//;
    is($error, "Trailing \\ in regex m/$pattern/",
        'dynamic trailing backslash uses the Perl diagnostic');
}

my $valid = '\\\\';
my $regex = eval { qr/$valid/ };
ok(defined($regex), 'paired trailing backslashes remain valid');
is($@, '', 'valid paired backslashes do not report a compile error');
ok("\\" =~ $regex, 'valid paired backslashes retain match semantics');

done_testing;

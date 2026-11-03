use strict;
use warnings;
use Test::More;

my $contents = "one\ntwo\nthree\n";
open my $fh, '<', \$contents or die "open scalar handle: $!";

<$fh>;
<$fh>;
is($., 2, 'reading updates the current handle line number');

seek($fh, 0, 0) or die "seek scalar handle: $!";
if (1) {
    local $. = 0;
}
is($., 0, 'localized assignment to $. remains on the filehandle after scope exit');
is(scalar <$fh>, "one\n", 'the handle was rewound');
is($., 1, 'reading after the rewind starts at line one');

my $other_contents = "other one\nother two\n";
open my $other, '<', \$other_contents or die "open second scalar handle: $!";
<$fh>;
<$fh>;
is($., 3, 'the original handle has advanced to line three');
{
    local $.;
    <$other>;
    is($., 1, 'reading another handle updates $. inside the localized scope');
}
is($., 3, 'scope exit restores the previously current handle');

done_testing;

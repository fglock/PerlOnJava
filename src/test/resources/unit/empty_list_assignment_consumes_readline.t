use strict;
use warnings;
use Test::More;

my $text = "first\nsecond\nthird\n";
open my $fh, '<', \$text or die "Could not open scalar handle: $!";
is(scalar(<$fh>), "first\n", 'read one line before resetting the handle');
seek($fh, 0, 0) or die "Could not seek scalar handle: $!";
() = <$fh>;
ok(eof($fh), 'empty list assignment evaluates readline in list context');

done_testing();

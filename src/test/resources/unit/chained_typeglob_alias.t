use strict;
use warnings;
use Test::More;

# A chained typeglob assignment aliases every slot.  AUTOLOAD writes its
# target into the scalar slot, which must therefore also become $_.
*AUTOLOAD = *_ = sub { print "$_\n" };
q<Just another Perl Hacker>->();

is($_, 'main::Just another Perl Hacker',
   'chained typeglob assignment aliases the scalar slot');

done_testing();

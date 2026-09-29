use strict;
use warnings;
use Test::More;

format PAGE_NUMBER_TOP =
@*
$% == 1 ? 'first page' : 'later page'
.

format PAGE_NUMBER_BODY =
body
.

my $output = '';
open my $fh, '>', \$output or die "open scalar handle: $!";
my $previous = select $fh;
local $^ = 'PAGE_NUMBER_TOP';
local $~ = 'PAGE_NUMBER_BODY';
local $= = 2;
local $- = 0;

write;
select $previous;

is($output, "first page\nbody\n",
   'a TOP format sees page number one in a numeric comparison');

done_testing;

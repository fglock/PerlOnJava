use strict;
use warnings;
use Test::More;
use B::Deparse;

my $warn = '';
local $SIG{__WARN__} = sub { $warn .= join '', @_ };

my $deparse = B::Deparse->new("-l");
isa_ok($deparse, 'B::Deparse');
is($warn, '', 'B::Deparse->new accepts flag-only options without warning');

done_testing();

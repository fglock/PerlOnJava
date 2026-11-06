use strict;
use warnings;
use re ();
use Test::More tests => 3;

my $pattern = qr/\A(?=a*)[0-9]a/;
my $optimization = re::optimization($pattern);
is($optimization->{stclass}, 'ANYOF[0-9]',
    'nullable lookahead preserves the following class as the start class');
ok('1a' =~ $pattern, 'the reported start class accepts a matching digit');
ok('aa' !~ $pattern, 'the nullable lookahead does not narrow the start class');

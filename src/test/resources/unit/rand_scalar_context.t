use strict;
use warnings;
use Test::More tests => 4;

my @values = qw(a b c d);
my $limit = rand @values;
ok($limit >= 0 && $limit < @values, 'rand uses array argument in scalar context');

my $aref = [qw(a b c d)];
my $picked = $aref->[rand @$aref];
my $found = grep { $_ eq $picked } @$aref;
ok($found, 'rand uses array dereference argument in scalar context');

my $code = q{
    sub {
        my %retval = @_;
        my $stuff;
        $stuff = ["aaa".."aaj"];
        $retval{Alpha} ||= $stuff->[rand @$stuff];
        return \%retval;
    }
};

my $generate = eval $code;
ok($generate, 'eval-generated sub with rand array dereference compiles');

my $thing = $generate->();
like($thing->{Alpha}, qr/^aa[a-j]$/, 'eval-generated rand array dereference returns an element');

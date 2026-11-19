use strict;
use warnings;
use Test::More;

BEGIN {
    require './src/main/perl/lib/Syntax/Keyword/Try.pm';
    $INC{'Syntax/Keyword/Try.pm'} = 'src/main/perl/lib/Syntax/Keyword/Try.pm';
}

my $result = eval q{
    use Syntax::Keyword::Try;
    my @events;
    try { push @events, 'try'; }
    catch ($error) { push @events, "catch:$error"; }
    finally { push @events, 'finally'; }
    join ',', @events;
};

if ($@ =~ /syntax error|finally/i) {
    plan skip_all => 'system Perl try syntax does not support finally';
}
die $@ if $@;
is($result, 'try,finally', 'finally executes after successful try');

done_testing();

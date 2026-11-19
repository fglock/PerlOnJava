use strict;
use warnings;
use Test::More;

if ($^X !~ /jperl-exec|jperl/i) {
    plan skip_all => 'catch without a variable is Syntax::Keyword::Try syntax';
}

BEGIN {
    require './src/main/perl/lib/Syntax/Keyword/Try.pm';
    $INC{'Syntax/Keyword/Try.pm'} = 'src/main/perl/lib/Syntax/Keyword/Try.pm';
}

my $caught = eval q{
    use Syntax::Keyword::Try;
    my $handled = 0;
    try { die "ignored\n" }
    catch { $handled = 1 }
    $handled;
};
die $@ if $@;
is($caught, 1, 'catch without a variable handles the exception');

done_testing();

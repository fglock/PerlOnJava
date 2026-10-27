use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken isweak);
our @destroyed;
{ package BorrowedArgumentGuard; sub DESTROY { push @main::destroyed, $_[0]{name} } }
sub clear_and_weaken_copy {
    my ($copy, $how) = @_;
    if ($how eq 'assign') { @_ = () } else { undef @_ }
    weaken($copy);
    ok(isweak($copy), "$how leaves the independently weakened copy alive");
}
for my $how ('assign', 'undef') {
    my $owner = bless { name => $how }, 'BorrowedArgumentGuard';
    my $weak = $owner;
    weaken($weak);
    clear_and_weaken_copy($owner, $how);
    ok(defined $weak, "$how of arguments preserves the caller's counted owner");
    is($owner->{name}, $how, "$how preserves the caller's value");
    undef $owner;
    ok(!defined $weak, "$how leaves exactly one caller owner to release");
}
is_deeply(\@destroyed, ['assign', 'undef'], 'caller owners are destroyed exactly once');
done_testing();

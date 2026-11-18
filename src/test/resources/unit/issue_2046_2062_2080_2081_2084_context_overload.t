use strict;
use warnings;
use Test::More;
use FindBin;
use lib "$FindBin::Bin/fixtures";
use List::Util::PP qw(pairgrep);

sub override_local_hash_slice {
    my ($target, %values) = @_;
    my @keys = keys %values;
    my $inside;
    {
        local(@$target{@keys}) = values %values;
        $inside = ref($target->{x});
    }
    return ($inside, ref($target->{x}), $target->{x}->());
}
my %target = (x => sub { 'outer' });
subtest 'localized hash-slice list context' => sub {
    my ($inside, $after, $value) = override_local_hash_slice(\%target, x => sub { 'inner' });
    is($inside, 'CODE', 'localized hash-slice RHS is list context');
    is($after, 'CODE', 'localized hash-slice restores original callback');
    is($value, 'outer', 'original callback remains callable');
};

{
    package Issue2080::Reader;
    use overload '<>' => sub { return wantarray ? qw(a b c) : 'a' };
    sub new { bless {}, shift }
}
subtest 'overloaded readline context' => sub {
    my $reader = Issue2080::Reader->new;
    is_deeply([<$reader>], [qw(a b c)], 'overloaded readline preserves list context');
    is(scalar(<$reader>), 'a', 'overloaded readline preserves scalar context');
};

subtest 'pairgrep context' => sub {
    my $pair_count = scalar pairgrep { $b & 2 } one => 1, two => 2, three => 3;
    is($pair_count, 2, 'pairgrep scalar context counts matching pairs');
    is_deeply([pairgrep { $b & 2 } one => 1, two => 2, three => 3],
        [two => 2, three => 3], 'pairgrep list context returns matching pairs');
};

my $issue_map_sub_context;
sub issue_record_context_from_map_block {
    my $context = wantarray;
    $issue_map_sub_context = !defined($context) ? 'void' : $context ? 'list' : 'scalar';
}
subtest 'subroutine called from map block keeps list context' => sub {
    map { issue_record_context_from_map_block() } 1;
    is($issue_map_sub_context, 'list', 'map block subroutine call receives list context');
};

{
    package Issue2062::Unknown;
    use Carp qw(confess);
    use overload
        '+' => sub { confess("math cannot be performed") },
        '""' => sub { die "string conversion attempted\n" };
    sub new { bless {}, shift }
}
subtest 'overloaded post-increment exception' => sub {
    my $unknown = Issue2062::Unknown->new;
    eval { $unknown++ };
    like($@, qr/^math cannot be performed/, 'post-increment preserves overload exception');
};

{
    package Issue2046::Lazy;
    use overload '&{}' => sub {
        $_[0] = sub { 'built' };
        return $_[0];
    };
    sub new { bless {}, shift }
}
subtest 'self-mutating code dereference' => sub {
    my $lazy = Issue2046::Lazy->new;
    is($lazy->(), 'built', '&{} can mutate proxy and return itself');
};

done_testing();

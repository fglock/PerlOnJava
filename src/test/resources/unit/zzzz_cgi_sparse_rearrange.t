use warnings;
use Test::More tests => 3;

sub rearrange_like_cgi {
    my ($order, @param) = @_;
    my %position;
    my $i = 0;
    for (@$order) {
        for (ref($_) eq 'ARRAY' ? @$_ : $_) {
            $position{lc($_)} = $i;
        }
        $i++;
    }
    my %params = @param;
    my @result;
    $#result = $#$order;
    for my $key (keys %params) {
        (my $normalized = lc($key)) =~ s/^-//;
        $result[$position{$normalized}] = $params{$key}
            if exists $position{$normalized};
    }
    @result;
}

{
    package Local::CGIShape;
    sub new { bless {}, shift }
    sub group {
        my ($self, @p) = @_;
        my ($name,$values,$defaults,$linebreak,$labels,$labelattributes,
            $attributes,$rows,$columns,$rowheaders,$colheaders,
            $override,$nolabels,$tabindex,$disabled,@other) =
            main::rearrange_like_cgi(
                [NAME,[VALUES,VALUE],[DEFAULT,DEFAULTS],LINEBREAK,LABELS,LABELATTRIBUTES,
                 ATTRIBUTES,ROWS,[COLUMNS,COLS],[ROWHEADERS,ROWHEADER],[COLHEADERS,COLHEADER],
                 [OVERRIDE,FORCE],NOLABELS,TABINDEX,DISABLED], @p);
        my %disabled;
        for (@{$disabled}) {
            $disabled{$_} = 1;
        }
        return ($name, $values, $defaults, scalar(@other));
    }
}

my ($name, $values, $defaults, $other) = Local::CGIShape->new->group(
    -name => 'game',
    -values => [qw(checkers chess cribbage)],
    -defaults => ['cribbage'],
);
is($name, 'game', 'named value survives sparse rearrangement');
is_deeply($values, [qw(checkers chess cribbage)], 'array reference survives sparse rearrangement');
is_deeply([$defaults, $other], [['cribbage'], 0], 'sparse trailing values flatten safely');

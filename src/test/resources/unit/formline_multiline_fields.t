use strict;
use warnings;
use Test::More;

{
    local $^A = '';
    my $picture = '1^*2 3@*4';
    my $fill = 'N';
    my $all = "N\nMoo!";
    formline $picture, $fill, $all;
    is($^A, "1N2 3N\nMoo!4", 'formline formats ^* and @* fields instead of copying their pictures');
}

{
    local $^A = '';
    formline '3@*4', "N\n";
    is($^A, '3N4', '@* consumes its terminal newline before following picture text');
}

{
    local $^A = '';
    formline '@### @0## @###. @##.## @0#.##', 9999.6, 1, 0, 1, 10;
    is($^A, '#### 0001    0.   1.00 010.00',
        'formline renders integer and decimal numeric pictures');
}

{
    local $^A = '';
    my $text = 'one two three';
    formline '^<<<', $text;
    is($^A, 'one', 'a text continuation field fills its picture width');
    is($text, 'two three', 'a text continuation field consumes the rendered words');
}

sub render_formline {
    my $picture = shift;
    local $^A = '';
    formline $picture, @_;
    return $^A;
}

{
    my $picture = '1^*2 3@*4';
    my $fill = 'N';
    my $all = "N\nMoo!";
    is(render_formline($picture, $fill, $all), "1N2 3N\nMoo!4",
        'formline expands @_ arguments in list context');
}

{
    package FormlineFetchProbe;
    sub TIESCALAR { bless { value => $_[1], fetches => 0 }, $_[0] }
    sub FETCH { ++$_[0]{fetches}; $_[0]{value} }
    sub STORE { $_[0]{value} = $_[1] }

    package main;
    tie my $value, 'FormlineFetchProbe', "N\nMoo!";
    my $probe = tied $value;
    local $^A = '';
    my $picture = '3@*4';
    formline $picture, $value;
    is($^A, "3N\nMoo!4", 'formline supplies a tied value to @*');
    is($probe->{fetches}, 1, 'formline fetches a supplied tied value once');
}

{
    my $marked_picture = '^<B~~^<<<';
    (my $space_picture = $marked_picture) =~ s/~/ /g;

    local $^A = '';
    my ($stop, $first, $second) = ('s', 'a', 'bc');
    formline $space_picture, $stop, $first, $second;
    my $expected = $^A;

    $^A = '';
    ($stop, $first, $second) = ('s', 'a', 'bc');
    formline $marked_picture, $stop, $first, $second;
    is($^A, $expected,
        'formline treats repeat markers as whitespace at their picture positions');
}

done_testing;

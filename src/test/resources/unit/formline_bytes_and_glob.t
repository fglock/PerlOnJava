use strict;
use warnings;
use Test::More;

{
    local $^A = '';
    my $picture = "X\n\x{100}" . ("\x80" x 200);
    my $expected = $picture;
    utf8::encode($expected);
    use bytes;
    formline($picture);
    is $^A, $expected, 'formline byte-mode output retains UTF-8 bytes';
}

{
    $^A = '';
    my $copy = *formline_glob_copy;
    my $result = eval { formline '^<<', $copy };
    is $@, '', 'glob value copied into a scalar is writable';
    ok $result, 'formline succeeds for copied glob value';
    is $^A, '*ma', 'copied glob contributes its formatted prefix';
    is $copy, 'in::formline_glob_copy', 'caret field consumes copied glob value';
}

{
    $^A = '';
    my $result = eval { formline '^<<', *formline_real_glob };
    like $@, qr/\AModification of a read-only value attempted /,
        'bare real glob is read-only to a caret field';
    is $result, undef, 'formline fails for bare real glob';
    is $^A, '*ma', 'formline retains text rendered before bare-glob failure';
}

done_testing;

use strict;
use warnings;
use Test::More;
use File::Spec ();

if ($^O eq 'MSWin32') {
    is(
        File::Spec->catpath('C:', 'foo', 'bar'),
        'C:foo\\bar',
        'Win32 catpath inserts separator between directory and file',
    );

    is(
        File::Spec->catpath('C:', 'foo', ''),
        'C:foo',
        'Win32 catpath accepts an empty file component',
    );

    is(
        File::Spec->catpath('\\\\server\\share', 'dir', 'file'),
        '\\\\server\\share\\dir\\file',
        'Win32 catpath joins UNC volume and relative directory',
    );

    is(
        File::Spec->catpath('', 'dir', 'file'),
        'dir\\file',
        'Win32 catpath uses backslash without a volume',
    );
} else {
    is(
        File::Spec->catpath('', '/tmp/'),
        '/tmp/',
        'catpath accepts a missing file component',
    );

    is(
        File::Spec->catpath('', '/tmp/', ''),
        '/tmp/',
        'catpath preserves a trailing directory separator with an empty file',
    );

    is(
        File::Spec->catpath('', '/tmp', 'file'),
        '/tmp/file',
        'catpath inserts a separator between directory and file',
    );

    is(
        File::Spec->catpath('ignored', '/tmp/', 'file'),
        '/tmp/file',
        'catpath ignores the volume on Unix',
    );
}

done_testing();

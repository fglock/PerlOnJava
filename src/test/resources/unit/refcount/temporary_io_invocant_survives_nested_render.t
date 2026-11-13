use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir);
use File::Spec;
use IO::File;

BEGIN {
    eval { require Template; 1 } or plan skip_all => 'Template Toolkit is not installed';
}

{
    package OutputPath;
    sub new { bless { path => $_[1] }, $_[0] }
    sub openw { return IO::File->new($_[0]->{path}, 'w') }
}

{
    package TemplateRenderer;
    sub get_content_for {
        my ($self, $source_path, $variables) = @_;
        my $content = '';
        my $template = Template->new({ ABSOLUTE => 1 }) or die Template->error();
        $template->process($source_path, $variables, \$content) or die $template->error();
        return $content;
    }
}

my $dir = tempdir(CLEANUP => 1);
my $source_path = File::Spec->catfile($dir, 'source.html.tt');
my $output_path = File::Spec->catfile($dir, 'output.html');
my $source = IO::File->new($source_path, 'w') or die "create source: $!";
$source->print("<title>[% title %]</title>\n");
$source->close or die "close source: $!";

my $output = OutputPath->new($output_path);
my $renderer = bless {}, 'TemplateRenderer';
$output->openw()->write($renderer->get_content_for(
    $source_path, { title => 'rendered' }
));

my $readback = IO::File->new($output_path, 'r') or die "open output: $!";
local $/;
my $content = <$readback>;
$readback->close;
is($content, "<title>rendered</title>\n", 'temporary output invocant survives nested template rendering');
done_testing();

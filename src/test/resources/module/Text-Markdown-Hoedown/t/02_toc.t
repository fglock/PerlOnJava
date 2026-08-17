use strict;
use Test::More;
use Text::Markdown::Hoedown;

my $src = <<'MD';
# 1
## 1.1
### 1.1.1
## 1.2
### 1.2.1
# 2
## 2.2
MD

is markdown_toc($src), <<'HTML';
<ul>
<li>
<a href="#toc_0">1</a>
<ul>
<li>
<a href="#toc_1">1.1</a>
<ul>
<li>
<a href="#toc_2">1.1.1</a>
</li>
</ul>
</li>
<li>
<a href="#toc_3">1.2</a>
<ul>
<li>
<a href="#toc_4">1.2.1</a>
</li>
</ul>
</li>
</ul>
</li>
<li>
<a href="#toc_5">2</a>
<ul>
<li>
<a href="#toc_6">2.2</a>
</li>
</ul>
</li>
</ul>
HTML

is markdown($src, toc_nesting_lvl => 0), <<'HTML';
<h1>1</h1>

<h2>1.1</h2>

<h3>1.1.1</h3>

<h2>1.2</h2>

<h3>1.2.1</h3>

<h1>2</h1>

<h2>2.2</h2>
HTML

done_testing;
